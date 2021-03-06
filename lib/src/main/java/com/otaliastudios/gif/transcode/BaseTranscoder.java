package com.otaliastudios.gif.transcode;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import com.otaliastudios.gif.internal.MediaCodecBuffers;
import com.otaliastudios.gif.sink.DataSink;
import com.otaliastudios.gif.source.DataSource;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A base implementation of {@link Transcoder} that reads
 * from {@link MediaExtractor} and does feeding and draining job.
 */
public abstract class BaseTranscoder implements Transcoder {

    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final DataSource mDataSource;
    private final DataSource.Chunk mDataChunk;
    private final DataSink mDataSink;

    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec mEncoder;
    private MediaCodecBuffers mEncoderBuffers;
    private boolean mEncoderStarted;
    private MediaFormat mActualOutputFormat;

    private boolean mIsEncoderEOS;
    private boolean mIsDataSourceEOS;

    @SuppressWarnings("WeakerAccess")
    protected BaseTranscoder(@NonNull DataSource dataSource,
                             @NonNull DataSink dataSink) {
        mDataSource = dataSource;
        mDataSink = dataSink;
        mDataChunk = new DataSource.Chunk();
    }

    @Override
    public final void setUp(@NonNull MediaFormat desiredOutputFormat) {
        try {
            mEncoder = MediaCodec.createEncoderByType(desiredOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        onConfigureEncoder(desiredOutputFormat, mEncoder);
        onStartEncoder(desiredOutputFormat, mEncoder);
        onStarted(mDataSource.getTrackFormat(), desiredOutputFormat, mEncoder);
    }

    /**
     * Wraps the configure operation on the encoder.
     * @param format output format
     * @param encoder encoder
     */
    protected void onConfigureEncoder(@NonNull MediaFormat format, @NonNull MediaCodec encoder) {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    /**
     * Wraps the start operation on the encoder.
     * @param format output format
     * @param encoder encoder
     */
    @CallSuper
    protected void onStartEncoder(@NonNull MediaFormat format, @NonNull MediaCodec encoder) {
        encoder.start();
        mEncoderStarted = true;
        mEncoderBuffers = new MediaCodecBuffers(encoder);
    }

    /**
     * Called when codecs have been started with the given formats.
     * @param inputFormat input format
     * @param outputFormat output format
     * @param encoder encoder
     */
    protected void onStarted(@NonNull MediaFormat inputFormat, @NonNull MediaFormat outputFormat,
                                   @NonNull MediaCodec encoder) {
    }

    @Override
    public final boolean isFinished() {
        return mIsEncoderEOS;
    }

    @Override
    public void release() {
        if (mEncoder != null) {
            if (mEncoderStarted) {
                mEncoder.stop();
                mEncoderStarted = false;
            }
            mEncoder.release();
            mEncoder = null;
        }
    }

    @Override
    public final boolean transcode(boolean forceInputEos) {
        boolean busy = false;
        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) busy = true;
        do {
            status = drainSource(0, forceInputEos);
            if (status != DRAIN_STATE_NONE) busy = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);

        while (feedEncoder(0)) busy = true;
        return busy;
    }

    @SuppressWarnings("SameParameterValue")
    private int drainSource(long timeoutUs, boolean forceInputEos) {
        if (mIsDataSourceEOS) {
            return DRAIN_STATE_NONE;
        }

        if (mDataSource.isDrained() || forceInputEos) {
            mIsDataSourceEOS = true;
            return DRAIN_STATE_NONE;
        }

        mDataSource.read(mDataChunk);
        onDrainSource(timeoutUs, mDataChunk.bitmap, mDataChunk.timestampUs, mDataSource.isDrained());
        return DRAIN_STATE_CONSUMED;
    }

    /**
     * Called after source has been drained.
     *
     * @param timeoutUs timeout in us
     * @param bitmap the source bitmap
     * @param endOfStream whether this is the last time
     */
    protected abstract void onDrainSource(long timeoutUs, @NonNull Bitmap bitmap,
                                          long presentationTimeUs,
                                          boolean endOfStream);

    /**
     * Called when the encoder has defined its actual output format.
     * @param format format
     */
    @CallSuper
    @SuppressWarnings("WeakerAccess")
    protected void onEncoderOutputFormatChanged(@NonNull MediaCodec encoder, @NonNull MediaFormat format) {
        if (mActualOutputFormat != null) {
            throw new RuntimeException("Audio output format changed twice.");
        }
        mActualOutputFormat = format;
        mDataSink.setFormat(mActualOutputFormat);
    }

    @SuppressWarnings("SameParameterValue")
    private boolean feedEncoder(long timeoutUs) {
        return onFeedEncoder(mEncoder, mEncoderBuffers, timeoutUs);
    }

    @SuppressWarnings("SameParameterValue")
    private int drainEncoder(long timeoutUs) {
        if (mIsEncoderEOS) return DRAIN_STATE_NONE;

        int result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                onEncoderOutputFormatChanged(mEncoder, mEncoder.getOutputFormat());
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderBuffers.onOutputBuffersChanged();
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        if (mActualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mIsEncoderEOS = true;
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }
        mDataSink.write(mEncoderBuffers.getOutputBuffer(result), mBufferInfo);
        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }

    /**
     * Called to feed the encoder with processed data.
     * @param encoder the encoder
     * @param timeoutUs a timeout for this op
     * @return true if we want to keep working
     */
    protected abstract boolean onFeedEncoder(@NonNull MediaCodec encoder,
                                             @NonNull MediaCodecBuffers encoderBuffers,
                                             long timeoutUs);
}
