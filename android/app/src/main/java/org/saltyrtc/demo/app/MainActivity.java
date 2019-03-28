/*
 * Copyright (c) 2016-2019 Threema GmbH
 *
 * Licensed under the Apache License, Version 2.0, <see LICENSE-APACHE file>
 * or the MIT license <see LICENSE-MIT file>, at your option. This file may not be
 * copied, modified, or distributed except according to those terms.
 */
package org.saltyrtc.demo.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.saltyrtc.chunkedDc.Chunker;
import org.saltyrtc.chunkedDc.Unchunker;
import org.saltyrtc.client.SaltyRTC;
import org.saltyrtc.client.SaltyRTCBuilder;
import org.saltyrtc.client.crypto.CryptoException;
import org.saltyrtc.client.crypto.CryptoProvider;
import org.saltyrtc.client.events.ApplicationDataEvent;
import org.saltyrtc.client.events.CloseEvent;
import org.saltyrtc.client.events.EventHandler;
import org.saltyrtc.client.events.HandoverEvent;
import org.saltyrtc.client.events.SignalingConnectionLostEvent;
import org.saltyrtc.client.events.SignalingStateChangedEvent;
import org.saltyrtc.client.exceptions.ConnectionException;
import org.saltyrtc.client.exceptions.InvalidKeyException;
import org.saltyrtc.client.exceptions.OverflowException;
import org.saltyrtc.client.keystore.Box;
import org.saltyrtc.client.keystore.KeyStore;
import org.saltyrtc.client.signaling.CloseCode;
import org.saltyrtc.client.signaling.state.SignalingState;
import org.saltyrtc.client.tasks.Task;
import org.saltyrtc.demo.app.utils.LazysodiumCryptoProvider;
import org.saltyrtc.demo.app.webrtc.PeerConnectionHelper;
import org.saltyrtc.demo.app.webrtc.SecureDataChannelContext;
import org.saltyrtc.tasks.webrtc.WebRTCTask;
import org.saltyrtc.tasks.webrtc.WebRTCTaskBuilder;
import org.saltyrtc.tasks.webrtc.WebRTCTaskVersion;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {
	@NonNull private static final String TAG = MainActivity.class.getName();
    @NonNull private static final CryptoProvider cryptoProvider = new LazysodiumCryptoProvider();

	@Nullable private SaltyRTC client;
    @Nullable private WebRTCTask task;
    @Nullable private PeerConnectionHelper pc;
    @Nullable private SecureDataChannelContext dc;

	private Button startButton;
    private Button stopButton;
    private TextView saltySignalingStateView;
    private TextView rtcSignalingStateView;
    private TextView rtcIceConnectionStateView;
    private TextView rtcIceGatheringStateView;
    private TextView saltyHandoverStateView;
    private LinearLayout messagesLayout;
    private ScrollView messagesScrollView;
    private EditText textInput;
    private Button sendButton;

	@SuppressLint("SetTextI18n")
	@Override
    @MainThread
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Get button views
		this.startButton = findViewById(R.id.button_start);
		this.stopButton = findViewById(R.id.button_stop);

		// Get state views
		this.saltySignalingStateView = findViewById(R.id.salty_signaling_state);
		this.rtcSignalingStateView = findViewById(R.id.rtc_signaling_state);
		this.rtcIceConnectionStateView = findViewById(R.id.rtc_ice_connection_state);
		this.rtcIceGatheringStateView = findViewById(R.id.rtc_ice_gathering_state);
		this.saltyHandoverStateView = findViewById(R.id.salty_handover_state);

		// Get other views
		this.messagesLayout = findViewById(R.id.messages);
		this.messagesScrollView = findViewById(R.id.messagesScroll);
		this.textInput = findViewById(R.id.chat_input);
		this.sendButton = findViewById(R.id.send_button);

		// Initialize states
        runOnUiThread(this::resetStates);
	}

	/**
	 * Reset all states to "Unknown".
	 */
    @UiThread
	private void resetStates() {
		this.setState(StateType.SALTY_SIGNALING, "Unknown");
		this.setState(StateType.RTC_SIGNALING, "Unknown");
		this.setState(StateType.RTC_ICE_CONNECTION, "Unknown");
		this.setState(StateType.RTC_ICE_GATHERING, "Unknown");
		this.setState(StateType.SALTY_HANDOVER, "Unknown");
	}

	@UiThread
	private void init() throws NoSuchAlgorithmException, InvalidKeyException, CryptoException {
		this.resetStates();

		// Create SaltyRTC tasks
		final Task[] tasks = new Task[] {
            new WebRTCTaskBuilder()
                .withVersion(WebRTCTaskVersion.V1)
                .build(),
            new WebRTCTaskBuilder()
                .withVersion(WebRTCTaskVersion.V0)
                .build()
        };

		// Create SaltyRTC client
		this.client = new SaltyRTCBuilder(cryptoProvider)
            .connectTo(Config.HOST, Config.PORT, SSLContext.getDefault())
            .withServerKey(Config.SERVER_KEY)
            .withKeyStore(new KeyStore(cryptoProvider, Config.PRIVATE_KEY))
            .withTrustedPeerKey(Config.TRUSTED_KEY)
            .withPingInterval(30)
            .withWebsocketConnectTimeout(15000)
            .usingTasks(tasks)
            .asResponder();

		// Bind events
		this.client.events.signalingStateChanged.register(this.onSignalingStateChanged);
        this.client.events.signalingConnectionLost.register(this.onSignalingConnectionLost);
        this.client.events.close.register(this.onClose);
		this.client.events.handover.register(this.onHandover);
		this.client.events.applicationData.register(this.onApplicationData);
	}

	/**
	 * On signaling state change.
	 */
	private final EventHandler<SignalingStateChangedEvent> onSignalingStateChanged = event -> {
        MainActivity.this.setState(StateType.SALTY_SIGNALING, event.getState().name());
        if (SignalingState.TASK == event.getState()) {
            // Store chosen task
            final Task task = this.client.getTask();
            if (!(task instanceof WebRTCTask)) {
                throw new RuntimeException("Unexpected task instance!");
            }
            this.task = (WebRTCTask) this.client.getTask();

            // Show UI elements
            runOnUiThread(() -> {
                MainActivity.this.textInput.setVisibility(View.VISIBLE);
                MainActivity.this.sendButton.setVisibility(View.VISIBLE);
            });

            // Initialise WebRTC peer-to-peer connection
            assert this.task != null;
            MainActivity.this.pc = new PeerConnectionHelper(this.task, this);
        }
        return false;
    };

    /**
     * On signaling connection lost.
     */
    private final EventHandler<SignalingConnectionLostEvent> onSignalingConnectionLost = event -> false;

	/**
	 * On close.
	 */
	private final EventHandler<CloseEvent> onClose = event -> {
        runOnUiThread(() -> MainActivity.this.stop(null));
        return false;
    };

    /**
     * On handover.
     */
    private final EventHandler<HandoverEvent> onHandover = event -> {
        // Enable UI elements
        runOnUiThread(() -> MainActivity.this.setState(StateType.SALTY_HANDOVER, "YES"));
        return false;
    };

    /**
     * On application message.
     */
    private final EventHandler<ApplicationDataEvent> onApplicationData = new EventHandler<ApplicationDataEvent>() {
        /**
         * To avoid string type compatibility problems, we encode data as UTF8 on the
         * browser side and decode the string from UTF8 here.
         */
        @Override
        public boolean handle(@NonNull final ApplicationDataEvent event) {
            final ByteBuffer buffer = ByteBuffer.wrap((byte[]) event.getData());
            Log.d(TAG, "Incoming application message: " + buffer.remaining() + " bytes");
            final String message = StandardCharsets.UTF_8.decode(buffer).toString();
            MainActivity.this.onMessage(message);
            return false;
        }
    };

	/**
	 * A new data channel was created.
	 */
    @AnyThread
	public void onDataChannel(@NonNull final DataChannel dc) {
		dc.registerObserver(new DataChannel.Observer() {
			@Override
			public void onBufferedAmountChange(final long bufferedAmount) {}

			@Override
			public void onStateChange() {}

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                Log.d(TAG, "Data channel " + dc.label() + " incomung chunk of length " +
                    buffer.data.remaining());
            }
        });

		// Handle incoming message
        final Unchunker.MessageListener messageListener = buffer -> {
            final long remaining = buffer.remaining();
            Log.d(TAG, "Data channel " + dc.label() + " incoming message of length " + remaining);
            final String message;
            if (remaining < 255) {
                message = StandardCharsets.UTF_8.decode(buffer).toString();
            } else {
                message = "[" + remaining / 1024 + " KiB binary data]";
            }
            this.onMessage(message);
        };

        // Create container
		this.dc = new SecureDataChannelContext(dc, Objects.requireNonNull(this.task), messageListener);

		// Enable send button
		runOnUiThread(() -> MainActivity.this.sendButton.setEnabled(true));
	}

	/**
	 * Start SaltyRTC client.
	 */
	@UiThread
	public void start(@NonNull final View view) {
		Log.d(TAG, "Starting SaltyRTC client...");
		try {
			this.init();
			Objects.requireNonNull(this.client).connect();
			this.startButton.setEnabled(false);
			this.stopButton.setEnabled(true);
			this.messagesLayout.removeAllViewsInLayout();
			this.setState(StateType.SALTY_HANDOVER, "NO");
		} catch (NoSuchAlgorithmException | InvalidKeyException | ConnectionException | CryptoException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Stop SaltyRTC client.
	 */
	@UiThread
	public void stop(@Nullable final View view) {
		if (this.dc != null) {
			Log.d(TAG, "Closing secure data channel...");
			this.dc.dc.close();
			this.dc.dc.dispose();
			this.dc = null;
		}

		if (this.task != null) {
            Log.d(TAG, "Stopping WebRTC task...");
            this.task.close(CloseCode.CLOSING_NORMAL);
        }

		if (this.client != null) {
            Log.d(TAG, "Stopping SaltyRTC client...");
            this.client.disconnect();
            this.client.events.clearAll();
            this.client = null;
        }

		if (this.pc != null) {
            Log.d(TAG, "Stopping WebRTC connection...");
            this.pc.close();
            this.pc = null;
        }

		this.startButton.setEnabled(true);
		this.stopButton.setEnabled(false);
		this.textInput.setVisibility(View.INVISIBLE);
		this.sendButton.setVisibility(View.INVISIBLE);
	}

	/**
	 * Set a state field.
	 *
	 * This method may be called from a background thread.
	 */
	@AnyThread
	public void setState(@NonNull final StateType type, @NonNull final String state) {
		runOnUiThread(() -> {
			switch (type) {
				case SALTY_SIGNALING:
                    MainActivity.this.saltySignalingStateView.setText(state);
					break;
				case RTC_SIGNALING:
                    MainActivity.this.rtcSignalingStateView.setText(state);
					break;
				case RTC_ICE_CONNECTION:
                    MainActivity.this.rtcIceConnectionStateView.setText(state);
					break;
				case RTC_ICE_GATHERING:
                    MainActivity.this.rtcIceGatheringStateView.setText(state);
					break;
				case SALTY_HANDOVER:
					MainActivity.this.saltyHandoverStateView.setText(state);
					break;
			}
		});

	}

    @AnyThread
    @NonNull private TextView getMessageTextView(final int colorResource, @NonNull final String text) {
		// Create text view
		final TextView view = new TextView(this);
		view.setText(text);
		view.setBackgroundColor(getResources().getColor(colorResource));

		// Set layout parameters
		final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		final int spacing = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, this.getResources().getDisplayMetrics());
		params.setMargins(spacing, spacing, spacing, 0);
		view.setPadding(spacing, spacing, spacing, spacing);
		view.setLayoutParams(params);

		return view;
	}

    /**
     * Handle incoming message.
     */
    @AnyThread
    public void onMessage(@NonNull final String message) {
        final View view = this.getMessageTextView(R.color.colorMessageIn, message);
        runOnUiThread(() -> MainActivity.this.showMessage(view));
    }

	/**
	 * Show message and scroll to bottom.
	 */
	@UiThread
	private void showMessage(@NonNull final View view) {
		MainActivity.this.messagesLayout.addView(view);
		MainActivity.this.messagesScrollView.post(() -> MainActivity.this.messagesScrollView.fullScroll(ScrollView.FOCUS_DOWN));
	}

	/**
	 * Send message via the secure data channel.
	 */
	@UiThread
	public void sendData(@NonNull final View view) {
	    // Fetch from input and encode
        final String text = this.textInput.getText().toString();
        final ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);

	    // Send message
	    final SecureDataChannelContext dc = Objects.requireNonNull(this.dc);
	    Log.d(TAG, "Data channel " + dc.dc.label() + " outgoing message of length " +
            bytes.remaining());
	    dc.enqueue(() -> {
	        // Encrypt
            final Box box;
            try {
                box = dc.crypto.encrypt(bytes.array());
            } catch (CryptoException error) {
                Log.e(TAG, "Unable to encrypt", error);
                return;
            } catch (OverflowException error) {
                Log.e(TAG, "CSN overflow", error);
                return;
            }

            // Chunkify
            Chunker chunker = dc.chunk(ByteBuffer.wrap(box.getData()));
            while ()

            // TODO: Wait for "ready" before sending each chunk
            // TODO: The runnable may not succeed before this is done (enqueue a Promise instead and chain the runnables)
        });


	    dc.fcdc.ready().thenRun(() -> {
	        // Encrypt

        });
        this.textInput.setText("");



		this.ufcdc.send(new DataChannel.Buffer(bytes, true));
		final View msgView = this.getMessageTextView(R.color.colorMessageOut, text);
		runOnUiThread(() -> MainActivity.this.showMessage(msgView));
		this.textInput.setText("");
	}

	/**
	 * Show key info.
	 */
    @UiThread
	public void showKeyInfo(@NonNull final View view) throws CryptoException {
		final AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setCancelable(true);
		builder.setTitle("Key Info");
		final String msg = "Public key: " +
				new KeyStore(cryptoProvider, Config.PRIVATE_KEY).getPublicKeyHex() +
				"\n\n" +
				"Private key: " +
				Config.PRIVATE_KEY +
				"\n\n" +
				"Trusted key: " +
				Config.TRUSTED_KEY +
				"\n\n" +
				"Server public key: " +
				Config.SERVER_KEY +
				"\n\n";
		builder.setMessage(msg);
		builder.setPositiveButton("OK", (dialogInterface, i) -> dialogInterface.dismiss());
		builder.create().show();
	}
}
