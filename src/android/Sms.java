package com.cordova.plugins.sms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import java.util.ArrayList;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.database.ContentObserver;


public class Sms extends CordovaPlugin {
	private static final String LOGTAG = "Sms";

	private static final String ACTION_START_WATCH = "startWatch";
    private static final String ACTION_STOP_WATCH = "stopWatch";
	private final String ACTION_SEND_SMS = "send";
	private static final String INTENT_FILTER_SMS_SENT = "SMS_SENT";

	private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";

	public static final String SMS_URI_ALL = "content://sms/";
    public static final String SMS_URI_INBOX = "content://sms/inbox";
    public static final String SMS_URI_SEND = "content://sms/sent";
    public static final String SMS_URI_DRAFT = "content://sms/draft";
    public static final String SMS_URI_OUTBOX = "content://sms/outbox";
    public static final String SMS_URI_FAILED = "content://sms/failed";
    public static final String SMS_URI_QUEUED = "content://sms/queued";

	public static final String BOX = "box";
    public static final String ADDRESS = "address";
    public static final String BODY = "body";
    public static final String READ = "read";
    public static final String SEEN = "seen";
    public static final String SUBJECT = "subject";
    public static final String SERVICE_CENTER = "service_center";
    public static final String DATE = "date";
    public static final String DATE_SENT = "date_sent";
    public static final String STATUS = "status";
    public static final String REPLY_PATH_PRESENT = "reply_path_present";
    public static final String TYPE = "type";
    public static final String PROTOCOL = "protocol";

	public static final int MESSAGE_TYPE_INBOX = 1;
    public static final int MESSAGE_TYPE_SENT = 2;
    public static final int MESSAGE_IS_NOT_READ = 0;
    public static final int MESSAGE_IS_READ = 1;
    public static final int MESSAGE_IS_NOT_SEEN = 0;
    public static final int MESSAGE_IS_SEEN = 1;

	private ContentObserver mObserver = null;
    private BroadcastReceiver mReceiver = null;
	private boolean mIntercept = false;
	private String lastFrom = "";
    private String lastContent = "";

	@Override
	public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {

		if (action.equals(ACTION_SEND_SMS)) {
            		cordova.getThreadPool().execute(new Runnable() {
                		@Override
                		public void run() {
                    			try {
                        			//parsing arguments
						String separator = ";";
						if (android.os.Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
							// See http://stackoverflow.com/questions/18974898/send-sms-through-intent-to-multiple-phone-numbers/18975676#18975676
							separator = ",";
						}
						String phoneNumber = args.getJSONArray(0).join(separator).replace("\"", "");
                        			String message = args.getString(1);
                        			String method = args.getString(2);
                        			boolean replaceLineBreaks = Boolean.parseBoolean(args.getString(3));

                        			// replacing \n by new line if the parameter replaceLineBreaks is set to true
                        			if (replaceLineBreaks) {
                            				message = message.replace("\\n", System.getProperty("line.separator"));
                        			}
                        			if (!checkSupport()) {
                            				callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "SMS not supported on this platform"));
                            				return;
                        			}
                        			if (method.equalsIgnoreCase("INTENT")) {
							invokeSMSIntent(phoneNumber, message);
                            				// always passes success back to the app
                            				callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
                        			} else {
                            				send(callbackContext, phoneNumber, message);
                        			}
                        			return;
                    			} catch (JSONException ex) {
                        			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
                    			}
                		}
            		});
            		return true;
		} else if (action.equals(ACTION_START_WATCH)) {					
			startWatch(callbackContext);																
			return true;
		} else if (action.equals(ACTION_STOP_WATCH)) {							
			stopWatch(callbackContext);			
			return true;
		}
		return false;
	}

	private boolean checkSupport() {
		Activity ctx = this.cordova.getActivity();
		return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
	}

	@SuppressLint("NewApi")
	private void invokeSMSIntent(String phoneNumber, String message) {
		Intent sendIntent;
		if ("".equals(phoneNumber) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			String defaultSmsPackageName = Telephony.Sms.getDefaultSmsPackage(this.cordova.getActivity());

			sendIntent = new Intent(Intent.ACTION_SEND);
			sendIntent.setType("text/plain");
			sendIntent.putExtra(Intent.EXTRA_TEXT, message);

			if (defaultSmsPackageName != null) {
				sendIntent.setPackage(defaultSmsPackageName);
			}
		} else {
			sendIntent = new Intent(Intent.ACTION_VIEW);
			sendIntent.putExtra("sms_body", message);
			// See http://stackoverflow.com/questions/7242190/sending-sms-using-intent-does-not-add-recipients-on-some-devices
			sendIntent.putExtra("address", phoneNumber);
			sendIntent.setData(Uri.parse("smsto:" + Uri.encode(phoneNumber)));
		}
		this.cordova.getActivity().startActivity(sendIntent);
	}

	private void send(final CallbackContext callbackContext, String phoneNumber, String message) {
		SmsManager manager = SmsManager.getDefault();
		final ArrayList<String> parts = manager.divideMessage(message);

		// by creating this broadcast receiver we can check whether or not the SMS was sent
		final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

			boolean anyError = false; //use to detect if one of the parts failed
			int partsCount = parts.size(); //number of parts to send

			@Override
			public void onReceive(Context context, Intent intent) {
				switch (getResultCode()) {
				case SmsManager.STATUS_ON_ICC_SENT:
				case Activity.RESULT_OK:
					break;
				case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
				case SmsManager.RESULT_ERROR_NO_SERVICE:
				case SmsManager.RESULT_ERROR_NULL_PDU:
				case SmsManager.RESULT_ERROR_RADIO_OFF:
					anyError = true;
					break;
				}
				// trigger the callback only when all the parts have been sent
				partsCount--;
				if (partsCount == 0) {
					if (anyError) {
						callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
					} else {
						callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
					}
					cordova.getActivity().unregisterReceiver(this);
				}
			}
		};

		// randomize the intent filter action to avoid using the same receiver
		String intentFilterAction = INTENT_FILTER_SMS_SENT + java.util.UUID.randomUUID().toString();
		this.cordova.getActivity().registerReceiver(broadcastReceiver, new IntentFilter(intentFilterAction));

		PendingIntent sentIntent = PendingIntent.getBroadcast(this.cordova.getActivity(), 0, new Intent(intentFilterAction), 0);

		// depending on the number of parts we send a text message or multi parts
		if (parts.size() > 1) {
			ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>();
			for (int i = 0; i < parts.size(); i++) {
				sentIntents.add(sentIntent);
			}
			manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
		}
		else {
			manager.sendTextMessage(phoneNumber, null, message, sentIntent, null);
		}
	}

	public void onDestroy() {
        stopWatch(null);
    }

	private void startWatch(CallbackContext callbackContext) {
        if (this.mObserver == null) {
            this.createContentObserver();
        }
        if (this.mReceiver == null) {
            this.createIncomingSMSReceiver();
        }
		if (callbackContext != null) {
			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
		}
    }

    private void stopWatch(CallbackContext callbackContext) {
        Activity ctx = this.cordova.getActivity();
        if (this.mReceiver != null) {
            ctx.unregisterReceiver(this.mReceiver);
            this.mReceiver = null;
        }
        if (this.mObserver != null) {
            ctx.getContentResolver().unregisterContentObserver(this.mObserver);
            this.mObserver = null;
        }
		if (callbackContext != null) {
			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
		}
    }
	
	private JSONObject getJsonFromCursor(Cursor cur) {
		JSONObject json = new JSONObject();
		
		int nCol = cur.getColumnCount();
		String keys[] = cur.getColumnNames();

		try {
			for(int j=0; j<nCol; j++) {
				switch(cur.getType(j)) {
				case Cursor.FIELD_TYPE_NULL:
					json.put(keys[j], null);
					break;
				case Cursor.FIELD_TYPE_INTEGER:
					json.put(keys[j], cur.getLong(j));
					break;
				case Cursor.FIELD_TYPE_FLOAT:
					json.put(keys[j], cur.getFloat(j));
					break;
				case Cursor.FIELD_TYPE_STRING:
					json.put(keys[j], cur.getString(j));
					break;
				case Cursor.FIELD_TYPE_BLOB:
					json.put(keys[j], cur.getBlob(j));
					break;
				}
			}
		} catch (Exception e) {
			return null;
		}

		return json;
    }

	private void fireEvent(final String event, JSONObject json) {
    	final String str = json.toString();
    	
        cordova.getActivity().runOnUiThread(new Runnable(){
            @Override
            public void run() {
            	String js = String.format("javascript:cordova.fireDocumentEvent(\"%s\", {\"data\":%s});", event, str);
            	webView.loadUrl( js );
            }
        });
    }
    
    private void onSMSArrive(JSONObject json) {
        String from = json.optString(ADDRESS);
        String content = json.optString(BODY);
        if (from.equals(this.lastFrom) && content.equals(this.lastContent)) {
            return;
        }
        this.lastFrom = from;
        this.lastContent = content;
        this.fireEvent("onSMSArrive", json);
    }

    protected void createIncomingSMSReceiver() {
        Activity ctx = this.cordova.getActivity();
        this.mReceiver = new BroadcastReceiver(){

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (SMS_RECEIVED.equals(action)) {
                    Bundle bundle;
                    if (Sms.this.mIntercept) {
                        this.abortBroadcast();
                    }
                    if ((bundle = intent.getExtras()) != null) {
                        Object[] pdus;
                        if ((pdus = (Object[])bundle.get("pdus")).length != 0) {
                            for (int i = 0; i < pdus.length; ++i) {
                                SmsMessage sms = SmsMessage.createFromPdu((byte[])((byte[])pdus[i]));
                                JSONObject json = Sms.this.getJsonFromSmsMessage(sms);
                                Sms.this.onSMSArrive(json);
                            }
                        }
                    }
                }
            }
        };
        String[] filterstr = new String[]{SMS_RECEIVED};
        for (int i = 0; i < filterstr.length; ++i) {
            IntentFilter filter = new IntentFilter(filterstr[i]);
            filter.setPriority(100);
            ctx.registerReceiver(this.mReceiver, filter);
        }
    }

    protected void createContentObserver() {	
        Activity ctx = cordova.getActivity();		
        this.mObserver = new ContentObserver(new Handler()){			
            public void onChange(boolean selfChange) {
                this.onChange(selfChange, null);
            }
			
            public void onChange(boolean selfChange, Uri uri) {
                ContentResolver resolver = cordova.getActivity().getContentResolver(); 
               
                int id = -1;
                String str;
                if (uri != null && (str = uri.toString()).startsWith(SMS_URI_ALL)) {
                    try {
                        id = Integer.parseInt(str.substring(SMS_URI_ALL.length()));
                       
                    }
                    catch (NumberFormatException var6_6) {
                        // empty catch block
                    }
                }
                if (id == -1) {
                    uri = Uri.parse(SMS_URI_INBOX);
                }
                Cursor cur = resolver.query(uri, null, null, null, "_id desc");
                if (cur != null) {
                    int n = cur.getCount();
                   
                    if (n > 0 && cur.moveToFirst()) {
                        JSONObject json;
                        if ((json = Sms.this.getJsonFromCursor(cur)) != null) {
                            onSMSArrive(json);
                        } else {
                            
                        }
                    }
                    cur.close();
                }
            }
        };		
        ctx.getContentResolver().registerContentObserver(Uri.parse(SMS_URI_INBOX), true, this.mObserver);			
    }

	private JSONObject getJsonFromSmsMessage(SmsMessage sms) {
    	JSONObject json = new JSONObject();
    	
        try {
        	json.put( ADDRESS, sms.getOriginatingAddress() );
        	json.put( BODY, sms.getMessageBody() ); // May need sms.getMessageBody.toString()
        	json.put( DATE_SENT, sms.getTimestampMillis() );
        	json.put( DATE, System.currentTimeMillis() );
        	json.put( READ, MESSAGE_IS_NOT_READ );
        	json.put( SEEN, MESSAGE_IS_NOT_SEEN );
        	json.put( STATUS, sms.getStatus() );
        	json.put( TYPE, MESSAGE_TYPE_INBOX );
        	json.put( SERVICE_CENTER, sms.getServiceCenterAddress());
        	
        } catch ( Exception e ) { 
            e.printStackTrace(); 
        }

    	return json;
    }
}
