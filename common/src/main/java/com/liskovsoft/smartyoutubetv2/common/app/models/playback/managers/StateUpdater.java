package com.liskovsoft.smartyoutubetv2.common.app.models.playback.managers;

import android.app.Activity;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.PlayerEventListenerHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.autoframerate.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StateUpdater extends PlayerEventListenerHelper {
    private boolean mIsPlaying;
    private float mSpeed = -1;
    private int mRepeatMode = 0;
    private FormatItem mVideoFormat;
    private FormatItem mAudioFormat;
    private FormatItem mSubtitleFormat;
    private static final long MUSIC_VIDEO_LENGTH_MS = 6 * 60 * 1000;
    // Don't store state inside Video object.
    // As one video might correspond to multiple Video objects.
    private final Map<String, State> mStates = new HashMap<>();

    private static class State {
        public final long positionMs;
        private final long lengthMs;

        public State(long positionMs) {
            this(positionMs, -1);
        }

        public State(long positionMs, long lengthMs) {
            this.positionMs = positionMs;
            this.lengthMs = lengthMs;
        }
    }

    @Override
    public void onActivity(Activity activity) {
        super.onActivity(activity);

        mVideoFormat = AppPrefs.instance(mActivity).getFormat(FormatItem.TYPE_VIDEO, FormatItem.VIDEO_HD_AVC);
        mAudioFormat = AppPrefs.instance(mActivity).getFormat(FormatItem.TYPE_AUDIO, FormatItem.AUDIO_HQ_MP4A);
        mSubtitleFormat = AppPrefs.instance(mActivity).getFormat(FormatItem.TYPE_SUBTITLE, null);
    }

    /**
     * Fired after user clicked on video in browse activity<br/>
     * or video is opened from the intent
     */
    @Override
    public void openVideo(Video item) {
        ensureVideoSize(item); // reset position of music videos

        mIsPlaying = true; // video just added

        // Ensure that we aren't running on presenter init stage
        if (mController != null && mController.isEngineBlocked()) {
            // In background mode some event not called.
            // So, for proper state persistence, we need to save state here.
            saveState();
        }
    }

    @Override
    public boolean onPreviousClicked() {
        boolean isShortVideo = mController.getPositionMs() > 10_000 && mController.getLengthMs() < MUSIC_VIDEO_LENGTH_MS;

        if (isShortVideo) {
            mController.setPositionMs(0);
            return true;
        }

        saveState();
        return false;
    }

    @Override
    public boolean onNextClicked() {
        saveState();
        return false;
    }

    @Override
    public void onSuggestionItemClicked(Video item) {
        saveState();
    }

    @Override
    public void onEngineInitialized() {
        mController.setRepeatMode(mRepeatMode);
    }

    @Override
    public void onEngineReleased() {
        saveState();
    }

    @Override
    public void onSourceChanged(Video item) {
        // called before engine attempt to auto select track by itself
        restoreFormat();
    }

    @Override
    public void onVideoLoaded(Video item) {
        // on this state video length is not undefined
        restorePosition(item);
        restoreSpeed();
    }

    @Override
    public void onPlay() {
        mIsPlaying = true;
        Helpers.disableScreensaver(mActivity);
    }

    @Override
    public void onPause() {
        mIsPlaying = false;
        Helpers.enableScreensaver(mActivity);
    }

    @Override
    public void onRepeatModeChange(int modeIndex) {
        mRepeatMode = modeIndex;
    }

    @Override
    public void onTrackSelected(FormatItem track) {
        if (track.getType() == FormatItem.TYPE_VIDEO && !mController.isInPIPMode()) {
            mVideoFormat = track;
        } else if (track.getType() == FormatItem.TYPE_AUDIO) {
            mAudioFormat = track;
        } else if (track.getType() == FormatItem.TYPE_SUBTITLE) {
            mSubtitleFormat = track;
        }

        AppPrefs.instance(mActivity).setFormat(track);
    }

    private void ensureVideoSize(Video item) {
        State state = mStates.get(item.videoId);

        // Trying to start music video from beginning
        if (state != null && state.lengthMs < MUSIC_VIDEO_LENGTH_MS) {
            mStates.remove(item.videoId);
        }
    }

    private void saveState() {
        trimStorage();

        Video video = mController.getVideo();

        if (video != null) {
            mStates.put(video.videoId, new State(mController.getPositionMs(), mController.getLengthMs()));
        }
    }

    private void trimStorage() {
        // NOP
    }

    private void restoreFormat() {
        if (mController.isInPIPMode()) {
            mController.selectFormat(FormatItem.VIDEO_SD_AVC);
        } else if (mVideoFormat != null) {
            mController.selectFormat(mVideoFormat);
        }

        if (mAudioFormat != null) {
            mController.selectFormat(mAudioFormat);
        }

        if (mSubtitleFormat != null) {
            mController.selectFormat(mSubtitleFormat);
        }
    }

    private void restorePosition(Video item) {
        State state = mStates.get(item.videoId);

        // internal storage has priority over item data loaded from network
        if (state == null && item.percentWatched > 0 && item.percentWatched < 100) {
            state = new State(getNewPosition(item.percentWatched));
        }

        boolean nearEnd = Math.abs(mController.getLengthMs() - mController.getPositionMs()) < 10_000;

        if (state != null && !nearEnd) {
            mController.setPositionMs(state.positionMs);
            mController.setPlay(mIsPlaying);
        } else {
            mController.setPlay(true); // start play immediately when state not found
        }
    }

    private void restoreSpeed() {
        if (mController.getLengthMs() - mController.getPositionMs() > 30_000) {
            mController.setSpeed(mSpeed);
        }
    }

    private long getNewPosition(int percentWatched) {
        long newPositionMs = mController.getLengthMs() / 100 * percentWatched;

        boolean samePositions = Math.abs(newPositionMs - mController.getPositionMs()) < 10_000;

        if (samePositions) {
            newPositionMs = -1;
        }

        return newPositionMs;
    }

    @Override
    public void onVideoSpeedClicked() {
        List<OptionItem> items = new ArrayList<>();

        // suppose live stream if buffering near the end
        // boolean isStream = Math.abs(player.getDuration() - player.getCurrentPosition()) < 10_000;
        intSpeedItems(items, new float[]{0.25f, 0.5f, 0.75f, 1.0f, 1.1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f});

        AppSettingsPresenter settingsPresenter = AppSettingsPresenter.instance(mActivity);
        settingsPresenter.clear();
        settingsPresenter.appendRadioCategory(mActivity.getString(R.string.video_speed), items);
        settingsPresenter.showDialog();
    }

    private void intSpeedItems(List<OptionItem> items, float[] speedValues) {
        for (float speed : speedValues) {
            items.add(UiOptionItem.from(
                    String.valueOf(speed),
                    optionItem -> {
                        mController.setSpeed(speed);
                        mSpeed = speed;
                    },
                    mController.getSpeed() == speed));
        }
    }
}
