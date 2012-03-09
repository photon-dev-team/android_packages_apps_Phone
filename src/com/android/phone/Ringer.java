/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.phone;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.Phone;
/**
 * Ringer manager for the Phone app.
 */
public class Ringer {
	private static final String LOG_TAG = "Ringer";
	private static final boolean DBG = 
			(PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

	/** The singleton instance. */
	private static Ringer sInstance;
	private RingHandler mRingHandler;

	// These two are used from both threads
	private IPowerManager mPowerManager;
	private Context mContext;

	/**
	 * Initialize the singleton Ringer instance.
	 * This is only done once, at startup, from PhoneApp.onCreate().
	 */
	static Ringer init(Context context) {
		synchronized (Ringer.class) {
			if (sInstance == null) {
				sInstance = new Ringer(context);
			} else {
				Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
			}
			return sInstance;
		}
	}

	/** Private constructor; @see init() */
	private Ringer(Context context) {
		mContext = context;
		mPowerManager = IPowerManager.Stub.asInterface(
				ServiceManager.getService(Context.POWER_SERVICE));
	}

	/**
	 * After a radio technology change, e.g. from CDMA to GSM or vice versa,
	 * the Context of the Ringer has to be updated. This is done by that function.
	 *
	 * @parameter Phone, the new active phone for the appropriate radio
	 * technology
	 */
	void updateRingerContextAfterRadioTechnologyChange(Phone phone) {
		if(DBG) Log.d(LOG_TAG, "updateRingerContextAfterRadioTechnologyChange...");
		mContext = phone.getContext();
	}

	/**
	 * Starts ringing if not yet started, or restarts if it is ringing.
	 * Actual start can be delayed a bit.
	 * Subsequent calls to startRing() are ignored until after stopRing().
	 */
	public void startRing(Uri ringtoneUri) {
		if (DBG) log("startRing()...");

		AudioManager audioMgr = (AudioManager)mContext.getSystemService(
				Context.AUDIO_SERVICE);
		
		if (null == ringtoneUri) {
			ringtoneUri = Settings.System.DEFAULT_RINGTONE_URI;
		}

		int attentionLight = RingHandler.ATTN_LIGHT_INCOMING;
		if (PhoneApp.getInstance().showBluetoothIndication()) {
			attentionLight =RingHandler.ATTN_LIGHT_INCOMING_BT;
		}
		
		StartRingMsg msg = new StartRingMsg(ringtoneUri, attentionLight,
				(0 != audioMgr.getStreamVolume(AudioManager.STREAM_RING)),
				(audioMgr.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)));

		synchronized(this) {
		    getRingHandler().sendMessage(
		            getRingHandler().obtainMessage(RingHandler.MSG_START, msg));
		}
	}

	/**
	 * Stops the ringtone and/or vibrator if any of these are actually
	 * ringing/vibrating.
	 */
	public void stopRing() {
		if (DBG) log("stopRing()...");

		synchronized(this) {
		    getRingHandler().removeCallbacksAndMessages(null);
		    getRingHandler().sendMessageAtFrontOfQueue(
		            getRingHandler().obtainMessage(RingHandler.MSG_STOP));
		}
	}

	/**
	 * Repeats the ring, if needed.
	 * Does nothing if there is no ring in progress or ring is suspended.
	 * Ignored if invoked outside of startRing()/stopRing() call pair.
	 */
	public void continueRing() {
		if (DBG) log("repeatRing()...");
		
		synchronized(this) {
		    getRingHandler().sendEmptyMessage(RingHandler.MSG_CONTINUE);
		}
	}

	/**
	 * Silences the ring.
	 * Does nothing if there is no ring in progress.
	 * Ignored if invoked outside of startRing()/stopRing() call pair. 
	 * Subsequent invocations ignored, until after resumeRing(). 
	 */
	public void suspendRing() {
		if (DBG) log("suspendRing()...");
		
		synchronized(this) {
		    getRingHandler().sendEmptyMessage(RingHandler.MSG_SUSPEND);
		}
	}

	/**
	 * Resumes ring after it was silenced.
	 * 
	 * Does nothing if there is no ring in progress.
	 * Ignored if invoked outside of startRing()/stopRing() call pair.
	 * Subsequent invocations ignored, until after suspendRing().
	 */
	public void resumeRing() {
		if (DBG) log("resumeRing()...");
		
		synchronized(this) {
		    getRingHandler().sendEmptyMessage(RingHandler.MSG_RESUME);
		}
	}

	String getStateDescription() {
	    synchronized(this) {
	        return getRingHandler().getStateDescription();
	    }
	}
	/**
	 * Instantiates a RingHandler, if needed.
	 * RingHandler is running in a separate thread and serves ring start and
	 * stop requests.
	 * RingHandler's thread is reused for all calls.
	 * @return RingHandler 
	 */
	private RingHandler getRingHandler() {
		synchronized(this) {
			if (null == mRingHandler) {
				if (DBG) log("Allocating new ring handler");
				Thread thread = new Thread("ringer") {
					@Override
					public void run() {
						Looper.prepare();
                        synchronized(Thread.currentThread()) {
                            mRingHandler = new RingHandler(Looper.myLooper());
                            Thread.currentThread().notifyAll();
						}
						while (true) {
						    if (DBG) Log.d("Ringer/RingHandler",
						            "Entering serving loop");
						    Looper.loop();
						    synchronized(this) {
						        // If so happens that there is another ring to
						        // serve, don't die too young.
						        // Ignore other messages as they are not going
						        // to be handled anyway once we have reached 
						        // this stage.
						        if (!mRingHandler.hasMessages(
						                RingHandler.MSG_START)) {
						            mRingHandler = null;
						            break;
						        }
						    }
						}
					}
				};
				thread.start();
				synchronized(thread) {
					while (null == mRingHandler) {
						try {
							thread.wait();
						} catch (InterruptedException ex) {
						}
					}
				}
			}
		}

		return mRingHandler;
	}

	private class StartRingMsg {
		public Uri ringtoneUri;
		public long timeStamp;
		public int attentionLight;
		public boolean playsRingtone;
		public boolean vibrates;

		StartRingMsg(Uri uri, int attnLight, boolean ring, boolean vibe) {
			ringtoneUri = uri;
			attentionLight = attnLight;
			playsRingtone = ring;
			vibrates = vibe;
			timeStamp = SystemClock.elapsedRealtime();
		}
	}

	private class RingHandler extends Handler {
		private static final String LOG_TAG = "Ringer/RingHandler";
		
		/**
		 * @category Message constants
		 */
		/** Start to serve a call, obj is a StartRingMsg */
		private static final int MSG_START = 0;
		/** Vibe once, internal */
		private static final int MSG_VIBE = 1;
		/** Ring once, if needed, internal, obj can be a new ring Uri or null */
		private static final int MSG_RING = 2;
		/** Repeat ring, if needed */
		private static final int MSG_CONTINUE = 3;
		/** Silence ring */
		private static final int MSG_SUSPEND = 4;
		/** Resumes silenced ring, if needed */
		private static final int MSG_RESUME = 5;
		/** Stop serving a call */
		private static final int MSG_STOP = 6;
		/** Cleanup working thread since it was unused for too long, internal */
		private static final int MSG_CLEANUP = 8;
		

		/**
		 *  @category Timings
		 */
		/** How long to vibrate, ms */
		private static final int VIBRATE_LENGTH = 1000;
		/** How long to wait between vibrates, ms */
		private static final int PAUSE_LENGTH = 1000;
		/** Unused cleanup delay, ms */
		private static final int CLEANUP_DELAY = 5000;

		/**
		 * @category Attention light color codes
		 */
		private static final int ATTN_LIGHT_NONE = 0x00000000;
		private static final int ATTN_LIGHT_INCOMING = 0x00ffffff;
		private static final int ATTN_LIGHT_INCOMING_BT = 0x000000ff;

		/**
		 * @category States
		 */
		/** Ready for a call to serve */
		private static final int STATE_IDLE = 0;
		/** Actually serving a call */
		private static final int STATE_RINGING = 1;
		/** Ringing is (temporarily) suspended */
		private static final int STATE_SUSPENDED = 2;
		/** Current state */
		private int mState = STATE_IDLE;

		private Vibrator mVibrator = new Vibrator();
		private long mFirstRingEventTime = -1; // when ring is requested
		private long mFirstRingStartTime = -1; // when ring is actually started
		// Data for periodics and resumes
		private Ringtone mRingtone = null;
		private boolean mVibes = false;
		private int mAttentionLight = 0x00000000; 

		public RingHandler(Looper looper) {
			super(looper);
		}

		String getStateDescription() {
			String theState = "";

			switch (mState) {
			case STATE_IDLE: theState = "STATE_IDLE"; break;
			case STATE_RINGING: theState = "STATE_RINGING"; break;
			case STATE_SUSPENDED: theState = "STATE_SUSPENDED"; break;
			}

			return theState;
		}
		
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_START:
				if (DBG) Log.d(LOG_TAG, "MSG_START, state: " + mState);

				if (STATE_IDLE != mState) return; 
				if (DBG) Log.d(LOG_TAG, "MSG_START: Ringing for the first time");
				StartRingMsg request = (StartRingMsg)msg.obj;

				// We are in use !!!
				removeMessages(MSG_CLEANUP);
				
				mVibes = request.vibrates;
				mAttentionLight = request.attentionLight;
				mState = STATE_RINGING;
				
				if (mVibes) {
					if (DBG) Log.d(LOG_TAG, "MSG_START: Requested to vibe");
					// Engage vibration
					sendEmptyMessage(MSG_VIBE);
				}

				if (request.playsRingtone && null != request.ringtoneUri) {
					if (DBG) Log.d(LOG_TAG, "MSG_START: Requested to ring");
					// Engage ring
					sendMessage(obtainMessage(MSG_RING, request.ringtoneUri));
					mFirstRingEventTime = request.timeStamp;
				}

				try {
					if (DBG) Log.d(LOG_TAG, "MSG_START: Lighting up");
					mPowerManager.setAttentionLight(true, mAttentionLight);
				} catch (RemoteException ex) {
					// the other end of this binder call is in the system process.
				}
				break;
			case MSG_CONTINUE:
				if (DBG) Log.d(LOG_TAG, "MSG_CONTINUE, state: " + mState);
				if (STATE_RINGING != mState) return;
				
				// Use mFirstRingEventTime as implicit indication
				// that ring play was requested
				if (mFirstRingStartTime > 0 && mFirstRingEventTime > 0) {
					// For repeat rings, figure out by how much to delay
					// the ring so that it happens the correct amount of
					// time after the previous ring
					if (null != mRingtone && !mRingtone.isPlaying()) {
						if (DBG) Log.d(LOG_TAG, "MSG_CONTINUE: Replay");
						sendEmptyMessageDelayed(MSG_RING,
								mFirstRingStartTime - mFirstRingEventTime);
					}
				} else {
					// We've gotten two ring events so far, but the ring
					// still hasn't started. Reset the event time to the
					// time of this event to maintain correct spacing.
					if (DBG) Log.d(LOG_TAG, "MSG_CONTINUE: Rebase ring");
					mFirstRingEventTime = SystemClock.elapsedRealtime();
				}
				break;
			case MSG_VIBE:
				if (DBG) Log.d(LOG_TAG, "MSG_VIBE, state: " + mState);
				if (STATE_RINGING != mState) return;
				
				mVibrator.vibrate(VIBRATE_LENGTH);
				this.sendEmptyMessageDelayed(MSG_VIBE,
						VIBRATE_LENGTH + PAUSE_LENGTH);
				break;
			case MSG_RING:
				if (DBG) Log.d(LOG_TAG, "MSG_RING, state: " + mState);

				// Remember new ringtone regardless of the state,
				// as we will be unable to obtain it later otherwise.
				if (null != msg.obj) {
					// Requested to instantiate (switch to) a ringtone
					if (null != mRingtone && mRingtone.isPlaying()) {
						mRingtone.stop();
					}
					// Assuming that accessing a member is an atomic operation, 
					// we should be safe here with possible updates of context
					// in main thread
					Context ctx = mContext;
					if (DBG) Log.d(LOG_TAG, "MSG_RING: Resolving ringtone");
					mRingtone = RingtoneManager.getRingtone(ctx, (Uri)msg.obj);
				}
				
				if (STATE_RINGING != mState) return;

				// Allow ring to play to its end before following replay request
				if (null != mRingtone && !mRingtone.isPlaying()) {
					if (DBG) Log.d(LOG_TAG, "MSG_RING: Playing ringtone");
					PhoneUtils.setAudioMode();
					mRingtone.play();
					if (mFirstRingStartTime < 0) {
						mFirstRingStartTime = SystemClock.elapsedRealtime();
					}
				}
				break;
			case MSG_SUSPEND:
				if (DBG) Log.d(LOG_TAG, "MSG_SUSPEND, state: " + mState);
				if (STATE_RINGING != mState) return;

				mState = STATE_SUSPENDED;
				mFirstRingEventTime = -1;
				mFirstRingStartTime = -1;
				if (mVibes) {
					if (DBG) Log.d(LOG_TAG, "MSG_SUSPEND: Stop vibe");
					mVibrator.cancel();
				}
				if (null != mRingtone && mRingtone.isPlaying()) {
					if (DBG) Log.d(LOG_TAG, "MSG_SUSPEND: Stop ring");
					mRingtone.stop();
				}
				try {
					if (DBG) Log.d(LOG_TAG, "MSG_SUSPEND: Turn off the light");
					mPowerManager.setAttentionLight(false, ATTN_LIGHT_NONE);
				} catch (RemoteException ex) {
					// the other end of this binder call is in the system process.
				}
				break;
			case MSG_RESUME:
				if (DBG) Log.d(LOG_TAG, "MSG_RESUME, state: " + mState);
				if (STATE_SUSPENDED != mState) return;
				
				mState = STATE_RINGING;
				if (mVibes) {
					if (DBG) Log.d(LOG_TAG, "MSG_RESUME: Resume vibe");
					// Engage vibration
					sendEmptyMessage(MSG_VIBE);
				}
				if (null != mRingtone) {
					if (DBG) Log.d(LOG_TAG, "MSG_RESUME: Resume ring");
					mFirstRingEventTime = SystemClock.elapsedRealtime();
					sendEmptyMessage(MSG_RING);
				}
				try {
					if (DBG) Log.d(LOG_TAG, "MSG_SUSPEND: Light up");
					mPowerManager.setAttentionLight(true, mAttentionLight);
				} catch (RemoteException ex) {
					// the other end of this binder call is in the system process.
				}
				break;
			case MSG_STOP:
				if (DBG) Log.d(LOG_TAG, "MSG_STOP, state: " + mState);

				// Ready for the next call
				mState = STATE_IDLE;
				mFirstRingEventTime = -1;
				mFirstRingStartTime = -1;
				mVibrator.cancel();
				if (null != mRingtone) {
					PhoneUtils.setAudioMode();
					mRingtone.stop();
					mRingtone = null;
				}
				try {
					mPowerManager.setAttentionLight(false, ATTN_LIGHT_NONE);
				} catch (RemoteException ex) {
					// the other end of this binder call is in the system process.
				}

				sendEmptyMessageDelayed(MSG_CLEANUP, CLEANUP_DELAY);
				break;
			case MSG_CLEANUP:
				getLooper().quit();
				if (DBG) Log.d(LOG_TAG, "MSG_CLEANUP: Exiting serving loop");
				break;
			}
		}
	}

	private static void log(String msg) {
		Log.d(LOG_TAG, msg);
	}
}
