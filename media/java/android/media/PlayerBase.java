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
 * limitations under the License.
 */

package android.media;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;

import java.lang.IllegalArgumentException;
import java.util.Objects;

/**
 * Class to encapsulate a number of common player operations:
 *   - AppOps for OP_PLAY_AUDIO
 *   - more to come (routing, transport control)
 * @hide
 */
public abstract class PlayerBase {

    private final static String TAG = "PlayerBase";
    private final static boolean DEBUG = false;
    private static IAudioService sService; //lazy initialization, use getService()
    /** Debug app ops */
    protected static final boolean DEBUG_APP_OPS = Log.isLoggable(TAG + ".AO", Log.DEBUG);

    // parameters of the player that affect AppOps
    protected AudioAttributes mAttributes;
    protected float mLeftVolume = 1.0f;
    protected float mRightVolume = 1.0f;
    protected float mAuxEffectSendLevel = 0.0f;

    // for AppOps
    private IAppOpsService mAppOps;
    private IAppOpsCallback mAppOpsCallback;
    private boolean mHasAppOpsPlayAudio = true;
    private final Object mAppOpsLock = new Object();

    private final int mImplType;
    // uniquely identifies the Player Interface throughout the system (P I Id)
    private int mPlayerIId;

    private int mState;

    /**
     * Constructor. Must be given audio attributes, as they are required for AppOps.
     * @param attr non-null audio attributes
     * @param class non-null class of the implementation of this abstract class
     */
    PlayerBase(@NonNull AudioAttributes attr, int implType) {
        if (attr == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        mAttributes = attr;
        mImplType = implType;
        mState = AudioPlaybackConfiguration.PLAYER_STATE_IDLE;
    };

    /**
     * Call from derived class when instantiation / initialization is successful
     */
    protected void baseRegisterPlayer() {
        int newPiid = AudioPlaybackConfiguration.PLAYER_PIID_INVALID;
        IBinder b = ServiceManager.getService(Context.APP_OPS_SERVICE);
        mAppOps = IAppOpsService.Stub.asInterface(b);
        // initialize mHasAppOpsPlayAudio
        updateAppOpsPlayAudio_sync();
        // register a callback to monitor whether the OP_PLAY_AUDIO is still allowed
        mAppOpsCallback = new IAppOpsCallback.Stub() {
            public void opChanged(int op, int uid, String packageName) {
                synchronized (mAppOpsLock) {
                    if (op == AppOpsManager.OP_PLAY_AUDIO) {
                        updateAppOpsPlayAudio_sync();
                    }
                }
            }
        };
        try {
            mAppOps.startWatchingMode(AppOpsManager.OP_PLAY_AUDIO,
                    ActivityThread.currentPackageName(), mAppOpsCallback);
        } catch (RemoteException e) {
            mHasAppOpsPlayAudio = false;
        }
        try {
            if (mIPlayer == null) {
                throw new IllegalStateException("Cannot register a player with a null mIPlayer");
            }
            newPiid = getService().trackPlayer(new PlayerIdCard(mImplType, mAttributes, mIPlayer));
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, player will not be tracked", e);
        }
        mPlayerIId = newPiid;
    }

    /**
     * To be called whenever the audio attributes of the player change
     * @param attr non-null audio attributes
     */
    void baseUpdateAudioAttributes(@NonNull AudioAttributes attr) {
        if (attr == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        }
        try {
            getService().playerAttributes(mPlayerIId, attr);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, STARTED state will not be tracked", e);
        }
        synchronized (mAppOpsLock) {
            mAttributes = attr;
            updateAppOpsPlayAudio_sync();
        }
    }

    void baseStart() {
        if (DEBUG) { Log.v(TAG, "baseStart() piid=" + mPlayerIId); }
        try {
            mState = AudioPlaybackConfiguration.PLAYER_STATE_STARTED;
            getService().playerEvent(mPlayerIId, mState);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, STARTED state will not be tracked", e);
        }
        synchronized (mAppOpsLock) {
            if (isRestricted_sync()) {
                playerSetVolume(true/*muting*/,0, 0);
            }
        }
    }

    void basePause() {
        if (DEBUG) { Log.v(TAG, "basePause() piid=" + mPlayerIId); }
        try {
            mState = AudioPlaybackConfiguration.PLAYER_STATE_PAUSED;
            getService().playerEvent(mPlayerIId, mState);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, PAUSED state will not be tracked", e);
        }
    }

    void baseStop() {
        if (DEBUG) { Log.v(TAG, "baseStop() piid=" + mPlayerIId); }
        try {
            mState = AudioPlaybackConfiguration.PLAYER_STATE_STOPPED;
            getService().playerEvent(mPlayerIId, mState);
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, STOPPED state will not be tracked", e);
        }
    }

    void baseSetVolume(float leftVolume, float rightVolume) {
        synchronized (mAppOpsLock) {
            mLeftVolume = leftVolume;
            mRightVolume = rightVolume;
            if (isRestricted_sync()) {
                return;
            }
        }
        playerSetVolume(false/*muting*/,leftVolume, rightVolume);
    }

    int baseSetAuxEffectSendLevel(float level) {
        synchronized (mAppOpsLock) {
            mAuxEffectSendLevel = level;
            if (isRestricted_sync()) {
                return AudioSystem.SUCCESS;
            }
        }
        return playerSetAuxEffectSendLevel(false/*muting*/, level);
    }

    /**
     * To be called from a subclass release or finalize method.
     * Releases AppOps related resources.
     */
    void baseRelease() {
        if (DEBUG) { Log.v(TAG, "baseRelease() piid=" + mPlayerIId + " state=" + mState); }
        try {
            if (mState != AudioPlaybackConfiguration.PLAYER_STATE_RELEASED) {
                getService().releasePlayer(mPlayerIId);
                mState = AudioPlaybackConfiguration.PLAYER_STATE_RELEASED;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error talking to audio service, the player will still be tracked", e);
        }
        try {
            mAppOps.stopWatchingMode(mAppOpsCallback);
        } catch (RemoteException e) {
            // nothing to do here, the object is supposed to be released anyway
        }
    }

    /**
     * To be called whenever a condition that might affect audibility of this player is updated.
     * Must be called synchronized on mAppOpsLock.
     */
    void updateAppOpsPlayAudio_sync() {
        boolean oldHasAppOpsPlayAudio = mHasAppOpsPlayAudio;
        try {
            final int mode = mAppOps.checkAudioOperation(AppOpsManager.OP_PLAY_AUDIO,
                    mAttributes.getUsage(),
                    Process.myUid(), ActivityThread.currentPackageName());
            mHasAppOpsPlayAudio = (mode == AppOpsManager.MODE_ALLOWED);
        } catch (RemoteException e) {
            mHasAppOpsPlayAudio = false;
        }

        // AppsOps alters a player's volume; when the restriction changes, reflect it on the actual
        // volume used by the player
        try {
            if (oldHasAppOpsPlayAudio != mHasAppOpsPlayAudio) {
                if (mHasAppOpsPlayAudio) {
                    if (DEBUG_APP_OPS) {
                        Log.v(TAG, "updateAppOpsPlayAudio: unmuting player, vol=" + mLeftVolume
                                + "/" + mRightVolume);
                    }
                    playerSetVolume(false/*muting*/, mLeftVolume, mRightVolume);
                    playerSetAuxEffectSendLevel(false/*muting*/, mAuxEffectSendLevel);
                } else {
                    if (DEBUG_APP_OPS) {
                        Log.v(TAG, "updateAppOpsPlayAudio: muting player");
                    }
                    playerSetVolume(true/*muting*/, 0.0f, 0.0f);
                    playerSetAuxEffectSendLevel(true/*muting*/, 0.0f);
                }
            }
        } catch (Exception e) {
            // failing silently, player might not be in right state
        }
    }

    /**
     * To be called by the subclass whenever an operation is potentially restricted.
     * As the media player-common behavior are incorporated into this class, the subclass's need
     * to call this method should be removed, and this method could become private.
     * FIXME can this method be private so subclasses don't have to worry about when to check
     *    the restrictions.
     * @return
     */
    boolean isRestricted_sync() {
        // check app ops
        if (mHasAppOpsPlayAudio) {
            return false;
        }
        // check bypass flag
        if ((mAttributes.getAllFlags() & AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY) != 0) {
            return false;
        }
        // check force audibility flag and camera restriction
        if (((mAttributes.getAllFlags() & AudioAttributes.FLAG_AUDIBILITY_ENFORCED) != 0)
                && (mAttributes.getUsage() == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)) {
            boolean cameraSoundForced = false;
            try {
                cameraSoundForced = getService().isCameraSoundForced();
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot access AudioService in isRestricted_sync()");
            } catch (NullPointerException e) {
                Log.e(TAG, "Null AudioService in isRestricted_sync()");
            }
            if (cameraSoundForced) {
                return false;
            }
        }
        return true;
    }

    private static IAudioService getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        sService = IAudioService.Stub.asInterface(b);
        return sService;
    }

    //=====================================================================
    // Abstract methods a subclass needs to implement
    /**
     * Abstract method for the subclass behavior's for volume and muting commands
     * @param muting if true, the player is to be muted, and the volume values can be ignored
     * @param leftVolume the left volume to use if muting is false
     * @param rightVolume the right volume to use if muting is false
     */
    abstract void playerSetVolume(boolean muting, float leftVolume, float rightVolume);
    abstract int playerSetAuxEffectSendLevel(boolean muting, float level);
    abstract void playerStart();
    abstract void playerPause();
    abstract void playerStop();

    //=====================================================================
    /**
     * Implementation of IPlayer for all subclasses of PlayerBase
     */
    private IPlayer mIPlayer = new IPlayer.Stub() {
        @Override
        public void start() {
            playerStart();
        }

        @Override
        public void pause() {
            playerPause();
        }

        @Override
        public void stop() {
            playerStop();
        }

        @Override
        public void setVolume(float vol) {
            baseSetVolume(vol, vol);
        }
    };

    //=====================================================================
    /**
     * Class holding all the information about a player that needs to be known at registration time
     */
    public static class PlayerIdCard implements Parcelable {
        public final int mPlayerType;

        public final static int AUDIO_ATTRIBUTES_NONE = 0;
        public final static int AUDIO_ATTRIBUTES_DEFINED = 1;
        public final AudioAttributes mAttributes;
        public final IPlayer mIPlayer;

        PlayerIdCard(int type, @NonNull AudioAttributes attr, @NonNull IPlayer iplayer) {
            mPlayerType = type;
            mAttributes = attr;
            mIPlayer = iplayer;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPlayerType);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mPlayerType);
            mAttributes.writeToParcel(dest, 0);
            dest.writeStrongBinder(mIPlayer == null ? null : mIPlayer.asBinder());
        }

        public static final Parcelable.Creator<PlayerIdCard> CREATOR
        = new Parcelable.Creator<PlayerIdCard>() {
            /**
             * Rebuilds an PlayerIdCard previously stored with writeToParcel().
             * @param p Parcel object to read the PlayerIdCard from
             * @return a new PlayerIdCard created from the data in the parcel
             */
            public PlayerIdCard createFromParcel(Parcel p) {
                return new PlayerIdCard(p);
            }
            public PlayerIdCard[] newArray(int size) {
                return new PlayerIdCard[size];
            }
        };

        private PlayerIdCard(Parcel in) {
            mPlayerType = in.readInt();
            mAttributes = AudioAttributes.CREATOR.createFromParcel(in);
            // IPlayer can be null if unmarshalling a Parcel coming from who knows where
            final IBinder b = in.readStrongBinder();
            mIPlayer = (b == null ? null : IPlayer.Stub.asInterface(b));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || !(o instanceof PlayerIdCard)) return false;

            PlayerIdCard that = (PlayerIdCard) o;

            // FIXME change to the binder player interface once supported as a member
            return ((mPlayerType == that.mPlayerType) && mAttributes.equals(that.mAttributes));
        }
    }

    //=====================================================================
    // Utilities

    /**
     * Use to generate warning or exception in legacy code paths that allowed passing stream types
     * to qualify audio playback.
     * @param streamType the stream type to check
     * @throws IllegalArgumentException
     */
    public static void deprecateStreamTypeForPlayback(int streamType, String className,
            String opName) throws IllegalArgumentException {
        // STREAM_ACCESSIBILITY was introduced at the same time the use of stream types
        // for audio playback was deprecated, so it is not allowed at all to qualify a playback
        // use case
        if (streamType == AudioManager.STREAM_ACCESSIBILITY) {
            throw new IllegalArgumentException("Use of STREAM_ACCESSIBILITY is reserved for "
                    + "volume control");
        }
        Log.e(className, "Use of stream types is deprecated for operations other than " +
                "volume control.");
        Log.e(className, "See the documentation of " + opName + " for what to use instead with " +
                "android.media.AudioAttributes to qualify your playback use case");
    }
}
