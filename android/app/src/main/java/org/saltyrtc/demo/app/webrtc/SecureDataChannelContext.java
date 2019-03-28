package org.saltyrtc.demo.app.webrtc;

import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.util.Log;

import org.saltyrtc.chunkedDc.Chunker;
import org.saltyrtc.chunkedDc.Unchunker;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.crypto.DataChannelCryptoContext;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

@AnyThread
public class SecureDataChannelContext {
    @NonNull private static final String TAG = SecureDataChannelContext.class.getName();

    @NonNull public final DataChannel dc;
    @NonNull public final FlowControlledDataChannel fcdc;
    @NonNull public final DataChannelCryptoContext crypto;
    @NonNull private final Unchunker unchunker;
    @NonNull private CompletableFuture<?> queue;
    private int chunkLength;
    private long messageId = 0;

    public SecureDataChannelContext(
        @NonNull final DataChannel dc, @NonNull final WebRTCTask task,
        @NonNull final Unchunker.MessageListener messageListener) {
        this.dc = dc;

        // Wrap as unbounded, flow-controlled data channel
        this.fcdc = new FlowControlledDataChannel(dc);

        // Create crypto context
        // Note: We need to apply encrypt-then-chunk for backwards
        //       compatibility reasons.
        this.crypto = task.getCryptoContext(dc.id());

        // Create unchunker
        this.unchunker = new Unchunker();
        this.unchunker.onMessage(messageListener);

        // Bind state events
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(final long bufferedAmount) {}

            @Override
            public void onStateChange() {
                switch (dc.state()) {
                    case CONNECTING:
                        Log.d(TAG, "Data channel " + dc.label() + " connecting");
                        break;
                    case OPEN:
                        Log.i(TAG, "Data channel " + dc.label() + " open");

                        // Determine chunk length
                        // Note: Hard-coded because webrtc.org...
                        SecureDataChannelContext.this.chunkLength = 64 * 1024;
                        break;
                    case CLOSING:
                        Log.d(TAG, "Data channel " + dc.label() + " closing");
                        break;
                    case CLOSED:
                        Log.i(TAG, "Data channel " + dc.label() + " closed");
                        break;
                }
            }

            @Override
            public void onMessage(@NonNull final DataChannel.Buffer buffer) {}
        });

        // Initialise queue
        this.queue = this.fcdc.ready();
    }

    /**
     * Enqueue an operation to be run in order on this channel's write queue.
     */
    public void enqueue(@NonNull final Runnable operation) {
        this.queue = this.queue.thenRun(operation);
    }

    /**
     * Create a chunker for message fragmentation.
     *
     * @param buffer The message to be fragmented.
     */
    @NonNull public Chunker chunk(@NonNull final ByteBuffer buffer) {
        return new Chunker(this.messageId++, buffer, this.chunkLength);
    }

    /**
     * Hand in a chunk for reassembly.
     *
     * @param buffer The chunk to be added to the reassembly buffer.
     */
    public void unchunk(@NonNull final ByteBuffer buffer) {
        this.unchunker.add(buffer);
    }
}
