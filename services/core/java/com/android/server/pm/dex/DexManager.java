/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.os.RemoteException;
import android.os.storage.StorageManager;

import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.PackageManagerServiceCompilerMapping;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;

/**
 * This class keeps track of how dex files are used.
 * Every time it gets a notification about a dex file being loaded it tracks
 * its owning package and records it in PackageDexUsage (package-dex-usage.list).
 *
 * TODO(calin): Extract related dexopt functionality from PackageManagerService
 * into this class.
 */
public class DexManager {
    private static final String TAG = "DexManager";

    private static final boolean DEBUG = false;

    // Maps package name to code locations.
    // It caches the code locations for the installed packages. This allows for
    // faster lookups (no locks) when finding what package owns the dex file.
    private final Map<String, PackageCodeLocations> mPackageCodeLocationsCache;

    // PackageDexUsage handles the actual I/O operations. It is responsible to
    // encode and save the dex usage data.
    private final PackageDexUsage mPackageDexUsage;

    private final IPackageManager mPackageManager;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;

    // Possible outcomes of a dex search.
    private static int DEX_SEARCH_NOT_FOUND = 0;  // dex file not found
    private static int DEX_SEARCH_FOUND_PRIMARY = 1;  // dex file is the primary/base apk
    private static int DEX_SEARCH_FOUND_SPLIT = 2;  // dex file is a split apk
    private static int DEX_SEARCH_FOUND_SECONDARY = 3;  // dex file is a secondary dex

    public DexManager(IPackageManager pms, PackageDexOptimizer pdo,
            Installer installer, Object installLock) {
      mPackageCodeLocationsCache = new HashMap<>();
      mPackageDexUsage = new PackageDexUsage();
      mPackageManager = pms;
      mPackageDexOptimizer = pdo;
      mInstaller = installer;
      mInstallLock = installLock;
    }

    /**
     * Notify about dex files loads.
     * Note that this method is invoked when apps load dex files and it should
     * return as fast as possible.
     *
     * @param loadingPackage the package performing the load
     * @param dexPaths the list of dex files being loaded
     * @param loaderIsa the ISA of the app loading the dex files
     * @param loaderUserId the user id which runs the code loading the dex files
     */
    public void notifyDexLoad(ApplicationInfo loadingAppInfo, List<String> dexPaths,
            String loaderIsa, int loaderUserId) {
        try {
            notifyDexLoadInternal(loadingAppInfo, dexPaths, loaderIsa, loaderUserId);
        } catch (Exception e) {
            Slog.w(TAG, "Exception while notifying dex load for package " +
                    loadingAppInfo.packageName, e);
        }
    }

    private void notifyDexLoadInternal(ApplicationInfo loadingAppInfo, List<String> dexPaths,
            String loaderIsa, int loaderUserId) {
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            Slog.w(TAG, "Loading dex files " + dexPaths + " in unsupported ISA: " +
                    loaderIsa + "?");
            return;
        }

        for (String dexPath : dexPaths) {
            // Find the owning package name.
            DexSearchResult searchResult = getDexPackage(loadingAppInfo, dexPath, loaderUserId);

            if (DEBUG) {
                Slog.i(TAG, loadingAppInfo.packageName
                    + " loads from " + searchResult + " : " + loaderUserId + " : " + dexPath);
            }

            if (searchResult.mOutcome != DEX_SEARCH_NOT_FOUND) {
                // TODO(calin): extend isUsedByOtherApps check to detect the cases where
                // different apps share the same runtime. In that case we should not mark the dex
                // file as isUsedByOtherApps. Currently this is a safe approximation.
                boolean isUsedByOtherApps = !loadingAppInfo.packageName.equals(
                        searchResult.mOwningPackageName);
                boolean primaryOrSplit = searchResult.mOutcome == DEX_SEARCH_FOUND_PRIMARY ||
                        searchResult.mOutcome == DEX_SEARCH_FOUND_SPLIT;

                if (primaryOrSplit && !isUsedByOtherApps) {
                    // If the dex file is the primary apk (or a split) and not isUsedByOtherApps
                    // do not record it. This case does not bring any new usable information
                    // and can be safely skipped.
                    continue;
                }

                // Record dex file usage. If the current usage is a new pattern (e.g. new secondary,
                // or UsedBytOtherApps), record will return true and we trigger an async write
                // to disk to make sure we don't loose the data in case of a reboot.
                if (mPackageDexUsage.record(searchResult.mOwningPackageName,
                        dexPath, loaderUserId, loaderIsa, isUsedByOtherApps, primaryOrSplit)) {
                    mPackageDexUsage.maybeWriteAsync();
                }
            } else {
                // This can happen in a few situations:
                // - bogus dex loads
                // - recent installs/uninstalls that we didn't detect.
                // - new installed splits
                // If we can't find the owner of the dex we simply do not track it. The impact is
                // that the dex file will not be considered for offline optimizations.
                // TODO(calin): add hooks for move/uninstall notifications to
                // capture package moves or obsolete packages.
                if (DEBUG) {
                    Slog.i(TAG, "Could not find owning package for dex file: " + dexPath);
                }
            }
        }
    }

    /**
     * Read the dex usage from disk and populate the code cache locations.
     * @param existingPackages a map containing information about what packages
     *          are available to what users. Only packages in this list will be
     *          recognized during notifyDexLoad().
     */
    public void load(Map<Integer, List<PackageInfo>> existingPackages) {
        try {
            loadInternal(existingPackages);
        } catch (Exception e) {
            mPackageDexUsage.clear();
            Slog.w(TAG, "Exception while loading package dex usage. " +
                    "Starting with a fresh state.", e);
        }
    }

    public void notifyPackageInstalled(PackageInfo info, int userId) {
        cachePackageCodeLocation(info, userId);
    }

    private void cachePackageCodeLocation(PackageInfo info, int userId) {
        PackageCodeLocations pcl = mPackageCodeLocationsCache.get(info.packageName);
        if (pcl != null) {
            pcl.mergeAppDataDirs(info.applicationInfo, userId);
        } else {
            mPackageCodeLocationsCache.put(info.packageName,
                new PackageCodeLocations(info.applicationInfo, userId));
        }
    }

    private void loadInternal(Map<Integer, List<PackageInfo>> existingPackages) {
        Map<String, Set<Integer>> packageToUsersMap = new HashMap<>();
        // Cache the code locations for the installed packages. This allows for
        // faster lookups (no locks) when finding what package owns the dex file.
        for (Map.Entry<Integer, List<PackageInfo>> entry : existingPackages.entrySet()) {
            List<PackageInfo> packageInfoList = entry.getValue();
            int userId = entry.getKey();
            for (PackageInfo pi : packageInfoList) {
                // Cache the code locations.
                cachePackageCodeLocation(pi, userId);

                // Cache a map from package name to the set of user ids who installed the package.
                // We will use it to sync the data and remove obsolete entries from
                // mPackageDexUsage.
                Set<Integer> users = putIfAbsent(
                        packageToUsersMap, pi.packageName, new HashSet<>());
                users.add(userId);
            }
        }

        mPackageDexUsage.read();
        mPackageDexUsage.syncData(packageToUsersMap);
    }

    /**
     * Get the package dex usage for the given package name.
     * @return the package data or null if there is no data available for this package.
     */
    public PackageUseInfo getPackageUseInfo(String packageName) {
        return mPackageDexUsage.getPackageUseInfo(packageName);
    }

    /**
     * Perform dexopt on the package {@code packageName} secondary dex files.
     * @return true if all secondary dex files were processed successfully (compiled or skipped
     *         because they don't need to be compiled)..
     */
    public boolean dexoptSecondaryDex(String packageName, String compilerFilter, boolean force) {
        // Select the dex optimizer based on the force parameter.
        // Forced compilation is done through ForcedUpdatePackageDexOptimizer which will adjust
        // the necessary dexopt flags to make sure that compilation is not skipped. This avoid
        // passing the force flag through the multitude of layers.
        // Note: The force option is rarely used (cmdline input for testing, mostly), so it's OK to
        //       allocate an object here.
        PackageDexOptimizer pdo = force
                ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(mPackageDexOptimizer)
                : mPackageDexOptimizer;
        PackageUseInfo useInfo = getPackageUseInfo(packageName);
        if (useInfo == null || useInfo.getDexUseInfoMap().isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No secondary dex use for package:" + packageName);
            }
            // Nothing to compile, return true.
            return true;
        }
        boolean success = true;
        for (Map.Entry<String, DexUseInfo> entry : useInfo.getDexUseInfoMap().entrySet()) {
            String dexPath = entry.getKey();
            DexUseInfo dexUseInfo = entry.getValue();
            PackageInfo pkg = null;
            try {
                pkg = mPackageManager.getPackageInfo(packageName, /*flags*/0,
                    dexUseInfo.getOwnerUserId());
            } catch (RemoteException e) {
                throw new AssertionError(e);
            }
            // It may be that the package gets uninstalled while we try to compile its
            // secondary dex files. If that's the case, just ignore.
            // Note that we don't break the entire loop because the package might still be
            // installed for other users.
            if (pkg == null) {
                Slog.d(TAG, "Could not find package when compiling secondary dex " + packageName
                        + " for user " + dexUseInfo.getOwnerUserId());
                mPackageDexUsage.removeUserPackage(packageName, dexUseInfo.getOwnerUserId());
                continue;
            }
            int result = pdo.dexOptSecondaryDexPath(pkg.applicationInfo, dexPath,
                    dexUseInfo.getLoaderIsas(), compilerFilter, dexUseInfo.isUsedByOtherApps());
            success = success && (result != PackageDexOptimizer.DEX_OPT_FAILED);
        }
        return success;
    }

    /**
     * Reconcile the information we have about the secondary dex files belonging to
     * {@code packagName} and the actual dex files. For all dex files that were
     * deleted, update the internal records and delete any generated oat files.
     */
    public void reconcileSecondaryDexFiles(String packageName) {
        PackageUseInfo useInfo = getPackageUseInfo(packageName);
        if (useInfo == null || useInfo.getDexUseInfoMap().isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No secondary dex use for package:" + packageName);
            }
            // Nothing to reconcile.
            return;
        }
        Set<String> dexFilesToRemove = new HashSet<>();
        boolean updated = false;
        for (Map.Entry<String, DexUseInfo> entry : useInfo.getDexUseInfoMap().entrySet()) {
            String dexPath = entry.getKey();
            DexUseInfo dexUseInfo = entry.getValue();
            PackageInfo pkg = null;
            try {
                // Note that we look for the package in the PackageManager just to be able
                // to get back the real app uid and its storage kind. These are only used
                // to perform extra validation in installd.
                // TODO(calin): maybe a bit overkill.
                pkg = mPackageManager.getPackageInfo(packageName, /*flags*/0,
                    dexUseInfo.getOwnerUserId());
            } catch (RemoteException ignore) {
                // Can't happen, DexManager is local.
            }
            if (pkg == null) {
                // It may be that the package was uninstalled while we process the secondary
                // dex files.
                Slog.d(TAG, "Could not find package when compiling secondary dex " + packageName
                        + " for user " + dexUseInfo.getOwnerUserId());
                // Update the usage and continue, another user might still have the package.
                updated = mPackageDexUsage.removeUserPackage(
                        packageName, dexUseInfo.getOwnerUserId()) || updated;
                continue;
            }
            ApplicationInfo info = pkg.applicationInfo;
            int flags = 0;
            if (info.dataDir.equals(info.deviceProtectedDataDir)) {
                flags |= StorageManager.FLAG_STORAGE_DE;
            } else if (info.dataDir.equals(info.credentialProtectedDataDir)) {
                flags |= StorageManager.FLAG_STORAGE_CE;
            } else {
                Slog.e(TAG, "Could not infer CE/DE storage for package " + info.packageName);
                updated = mPackageDexUsage.removeUserPackage(
                        packageName, dexUseInfo.getOwnerUserId()) || updated;
                continue;
            }

            boolean dexStillExists = true;
            synchronized(mInstallLock) {
                try {
                    String[] isas = dexUseInfo.getLoaderIsas().toArray(new String[0]);
                    dexStillExists = mInstaller.reconcileSecondaryDexFile(dexPath, packageName,
                            pkg.applicationInfo.uid, isas, pkg.applicationInfo.volumeUuid, flags);
                } catch (InstallerException e) {
                    Slog.e(TAG, "Got InstallerException when reconciling dex " + dexPath +
                            " : " + e.getMessage());
                }
            }
            if (!dexStillExists) {
                updated = mPackageDexUsage.removeDexFile(
                        packageName, dexPath, dexUseInfo.getOwnerUserId()) || updated;
            }

        }
        if (updated) {
            mPackageDexUsage.maybeWriteAsync();
        }
    }

    /**
     * Return all packages that contain records of secondary dex files.
     */
    public Set<String> getAllPackagesWithSecondaryDexFiles() {
        return mPackageDexUsage.getAllPackagesWithSecondaryDexFiles();
    }

    /**
     * Retrieves the package which owns the given dexPath.
     */
    private DexSearchResult getDexPackage(
            ApplicationInfo loadingAppInfo, String dexPath, int userId) {
        // Ignore framework code.
        // TODO(calin): is there a better way to detect it?
        if (dexPath.startsWith("/system/framework/")) {
            new DexSearchResult("framework", DEX_SEARCH_NOT_FOUND);
        }

        // First, check if the package which loads the dex file actually owns it.
        // Most of the time this will be true and we can return early.
        PackageCodeLocations loadingPackageCodeLocations =
                new PackageCodeLocations(loadingAppInfo, userId);
        int outcome = loadingPackageCodeLocations.searchDex(dexPath, userId);
        if (outcome != DEX_SEARCH_NOT_FOUND) {
            // TODO(calin): evaluate if we bother to detect symlinks at the dexPath level.
            return new DexSearchResult(loadingPackageCodeLocations.mPackageName, outcome);
        }

        // The loadingPackage does not own the dex file.
        // Perform a reverse look-up in the cache to detect if any package has ownership.
        // Note that we can have false negatives if the cache falls out of date.
        for (PackageCodeLocations pcl : mPackageCodeLocationsCache.values()) {
            outcome = pcl.searchDex(dexPath, userId);
            if (outcome != DEX_SEARCH_NOT_FOUND) {
                return new DexSearchResult(pcl.mPackageName, outcome);
            }
        }

        // Cache miss. Return not found for the moment.
        //
        // TODO(calin): this may be because of a newly installed package, an update
        // or a new added user. We can either perform a full look up again or register
        // observers to be notified of package/user updates.
        return new DexSearchResult(null, DEX_SEARCH_NOT_FOUND);
    }

    private static <K,V> V putIfAbsent(Map<K,V> map, K key, V newValue) {
        V existingValue = map.putIfAbsent(key, newValue);
        return existingValue == null ? newValue : existingValue;
    }

    /**
     * Convenience class to store the different locations where a package might
     * own code.
     */
    private static class PackageCodeLocations {
        private final String mPackageName;
        private final String mBaseCodePath;
        private final Set<String> mSplitCodePaths;
        // Maps user id to the application private directory.
        private final Map<Integer, Set<String>> mAppDataDirs;

        public PackageCodeLocations(ApplicationInfo ai, int userId) {
            mPackageName = ai.packageName;
            mBaseCodePath = ai.sourceDir;
            mSplitCodePaths = new HashSet<>();
            if (ai.splitSourceDirs != null) {
                for (String split : ai.splitSourceDirs) {
                    mSplitCodePaths.add(split);
                }
            }
            mAppDataDirs = new HashMap<>();
            mergeAppDataDirs(ai, userId);
        }

        public void mergeAppDataDirs(ApplicationInfo ai, int userId) {
            Set<String> dataDirs = putIfAbsent(mAppDataDirs, userId, new HashSet<>());
            dataDirs.add(ai.dataDir);
        }

        public int searchDex(String dexPath, int userId) {
            // First check that this package is installed or active for the given user.
            // If we don't have a data dir it means this user is trying to load something
            // unavailable for them.
            Set<String> userDataDirs = mAppDataDirs.get(userId);
            if (userDataDirs == null) {
                Slog.w(TAG, "Trying to load a dex path which does not exist for the current " +
                        "user. dexPath=" + dexPath + ", userId=" + userId);
                return DEX_SEARCH_NOT_FOUND;
            }

            if (mBaseCodePath.equals(dexPath)) {
                return DEX_SEARCH_FOUND_PRIMARY;
            }
            if (mSplitCodePaths.contains(dexPath)) {
                return DEX_SEARCH_FOUND_SPLIT;
            }
            for (String dataDir : userDataDirs) {
                if (dexPath.startsWith(dataDir)) {
                    return DEX_SEARCH_FOUND_SECONDARY;
                }
            }

            // TODO(calin): What if we get a symlink? e.g. data dir may be a symlink,
            // /data/data/ -> /data/user/0/.
            if (DEBUG) {
                try {
                    String dexPathReal = PackageManagerServiceUtils.realpath(new File(dexPath));
                    if (dexPathReal != dexPath) {
                        Slog.d(TAG, "Dex loaded with symlink. dexPath=" +
                                dexPath + " dexPathReal=" + dexPathReal);
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }
            return DEX_SEARCH_NOT_FOUND;
        }
    }

    /**
     * Convenience class to store ownership search results.
     */
    private class DexSearchResult {
        private String mOwningPackageName;
        private int mOutcome;

        public DexSearchResult(String owningPackageName, int outcome) {
            this.mOwningPackageName = owningPackageName;
            this.mOutcome = outcome;
        }

        @Override
        public String toString() {
            return mOwningPackageName + "-" + mOutcome;
        }
    }


}
