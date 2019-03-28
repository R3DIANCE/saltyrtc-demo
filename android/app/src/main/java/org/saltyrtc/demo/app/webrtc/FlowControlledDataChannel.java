package org.saltyrtc.demo.app.webrtc;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.util.Log;

import org.saltyrtc.tasks.webrtc.exceptions.IllegalStateError;
import org.webrtc.DataChannel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A flow-controlled (sender side) data channel.
 */
@AnyThread
public class FlowControlledDataChannel {
    @NonNull private static final String TAG = FlowControlledDataChannel.class.getName();

    @NonNull private final Lock lock = new ReentrantLock();
    @NonNull private final DataChannel dc;
    private final long highWaterMark;
    @NonNull private CompletableFuture<?> readyFuture = CompletableFuture.completedFuture(null);

    /**
     * Create a flow-controlled (sender side) data channel.
     *
     * @param dc The data channel to be flow-controlled
     */
    public FlowControlledDataChannel(@NonNull final DataChannel dc) {
        this(dc, 256 * 1024, 1024 * 1024);
    }

    /**
     * Create a flow-controlled (sender side) data channel.
     *
     * @param dc The data channel to be flow-controlled
     * @param lowWaterMark The low water mark unpauses the data channel once
     *   the buffered amount of bytes becomes less or equal to it.
     * @param highWaterMark The high water mark pauses the data channel once
     *   the buffered amount of bytes becomes greater or equal to it.
     */
    public FlowControlledDataChannel(
        @NonNull final DataChannel dc, final long lowWaterMark, final long highWaterMark) {
        this.dc = dc;
        this.highWaterMark = highWaterMark;

        // Bind buffered amount update event
        this.dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(final long bufferedAmount) {
                lock.lock();
                try {
                    // Unpause once low water mark has been reached
                    if (bufferedAmount <= lowWaterMark &&
                        !FlowControlledDataChannel.this.readyFuture.isDone()) {
                        Log.d(TAG, FlowControlledDataChannel.this.dc.label() +
                            " resumed (buffered=" + bufferedAmount + ")");
                        FlowControlledDataChannel.this.readyFuture.complete(null);
                    }
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public void onStateChange() {}

            @Override
            public void onMessage(@NonNull final DataChannel.Buffer buffer) {}
        });
    }

    /**
     * A future whether the data channel is ready to be written on.
     */
    public @NonNull CompletableFuture<?> ready() {
        return this.readyFuture;
    }

    /**
     * Write a message to the data channel's internal buffer for delivery to
     * the remote side.
     *
     * Important: Before calling this, the `ready` Promise must be awaited.
     *
     * @param message The message to be sent.
     * @throws IllegalStateError in case the data channel is currently paused.
     */
    public void write(@NonNull final DataChannel.Buffer message) {
        // Note: Locked since the "onBufferedAmountChange" event may run in parallel to the send
        //       calls.
        lock.lock();
        try {
            // Throw if paused
            if (!this.readyFuture.isDone()) {
                throw new IllegalStateError("Unable to write, data channel is paused!");
            }

            // Try sending
            // Note: Technically we should be able to catch an Exception in case the
            //       underlying buffer is full. However, webrtc.org is utterly
            //       outdated and just closes when its buffer would overflow. Thus,
            //       we use a well-tested high water mark instead and try to never
            //       fill the buffer completely.
            this.dc.send(message);

            // Pause once high water mark has been reached
            final long bufferedAmount = this.dc.bufferedAmount();
            if (bufferedAmount >= this.highWaterMark) {
                this.readyFuture = new CompletableFuture<>();
                Log.d(TAG, this.dc.label() + " paused (buffered=" + bufferedAmount + ")");
            }
        } finally {
            lock.unlock();
        }
    }
}
