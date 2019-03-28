package org.saltyrtc.demo.app.webrtc;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;

import org.webrtc.DataChannel;

import java.util.concurrent.CompletableFuture;

/**
 * A flow-controlled (sender side) data channel that allows to queue an
 * infinite amount of messages.
 *
 * While this cancels the effect of the flow control, it prevents the data
 * channel's underlying buffer from becoming saturated by queueing all messages
 * in application space.
 */
@AnyThread
public class UnboundedFlowControlledDataChannel extends FlowControlledDataChannel {
    @NonNull private CompletableFuture<?> queue = this.ready();

    /**
     * Create a flow-controlled (sender side) data channel with an infinite
     * buffer.
     *
     * @param dc The data channel to be flow-controlled
     */
    public UnboundedFlowControlledDataChannel(@NonNull final DataChannel dc) {
        super(dc);
    }

    /**
     * Create a flow-controlled (sender side) data channel with an infinite
     * buffer.
     *
     * @param dc The data channel to be flow-controlled
     * @param lowWaterMark The low water mark unpauses the data channel once
     *   the buffered amount of bytes becomes less or equal to it.
     * @param highWaterMark The high water mark pauses the data channel once
     *   the buffered amount of bytes becomes greater or equal to it.
     */
    public UnboundedFlowControlledDataChannel(
        @NonNull final DataChannel dc, final long lowWaterMark, final long highWaterMark) {
        super(dc, lowWaterMark, highWaterMark);
    }

    /**
     * Write a message to the data channel's internal or application buffer for
     * delivery to the remote side.
     *
     * Warning: This method is not thread-safe.
     *
     * @param message The message to be sent.
     */
    public void write(@NonNull final DataChannel.Buffer message) {
        // Wait until ready, then write
        // Note: This very simple technique allows for ordered message
        //       queueing by using future chaining.
        // TODO: This is flawed since the inner thenRun is async!
        NOPE
        this.queue = this.queue.thenRun(() -> this.ready().thenRun(() -> super.write(message)));
    }
}
