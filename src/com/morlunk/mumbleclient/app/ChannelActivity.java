package com.morlunk.mumbleclient.app;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Observable;
import java.util.Observer;

import junit.framework.Assert;
import net.sf.mumble.MumbleProto.PermissionDenied.DenyType;
import net.sf.mumble.MumbleProto.Reject;
import net.sf.mumble.MumbleProto.UserRemove;
import net.sf.mumble.MumbleProto.UserState;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.SearchView;
import com.morlunk.mumbleclient.Globals;
import com.morlunk.mumbleclient.R;
import com.morlunk.mumbleclient.Settings;
import com.morlunk.mumbleclient.app.TokenDialogFragment.TokenDialogFragmentProvider;
import com.morlunk.mumbleclient.app.db.DbAdapter;
import com.morlunk.mumbleclient.app.db.Favourite;
import com.morlunk.mumbleclient.service.BaseServiceObserver;
import com.morlunk.mumbleclient.service.MumbleProtocol.DisconnectReason;
import com.morlunk.mumbleclient.service.MumbleService;
import com.morlunk.mumbleclient.service.MumbleService.LocalBinder;
import com.morlunk.mumbleclient.service.model.Channel;
import com.morlunk.mumbleclient.service.model.Message;
import com.morlunk.mumbleclient.service.model.User;


/**
 * An interface for the activity that manages the channel selection.
 * @author andrew
 *
 */
interface ChannelProvider {
	public User getCurrentUser();
	public User getUserWithIdentifier(int id);
	public void setChatTarget(User chatTarget);
	public void sendChannelMessage(String message);
	public void sendUserMessage(String string, User chatTarget);
	public MumbleService getService();
}


public class ChannelActivity extends SherlockFragmentActivity implements ChannelProvider, TokenDialogFragmentProvider, Observer {

	/*
	 * Disconnect extras sent in result intent.
	 */
	public static final String EXTRA_SERVER = "server";
	public static final String EXTRA_DISCONNECT_TYPE = "disconnect_type";
	public static final String EXTRA_REJECT_TYPE = "reject_type";
	public static final String EXTRA_REJECT_REASON = "reject_reason";
	public static final String EXTRA_KICK_ACTOR = "kick_actor";
	public static final String EXTRA_KICK_REASON = "kick_reason";
	public static final String EXTRA_GENERIC_REASON = "generic_reason";
	
	public static final String JOIN_CHANNEL = "join_channel";
	public static final String SAVED_STATE_VISIBLE_CHANNEL = "visible_channel";
	public static final String SAVED_STATE_CHAT_TARGET = "chat_target";
	public static final Integer PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32; // Undocumented feature! This will allow us to enable the phone proximity sensor.
	
	/**
	 * The MumbleService instance that drives this activity's data.
	 */
	private MumbleService mService;
	
	/**
	 * An observer that monitors the state of the service.
	 */
	private ChannelServiceObserver mObserver;
	
	/**
	 * Management of service connection state.
	 */
	private ServiceConnection conn = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			LocalBinder localBinder = (LocalBinder)service;
			mObserver = new ChannelServiceObserver();
			mService = localBinder.getService();
			mService.registerObserver(mObserver);
	        
			// If we're not going to receive the onConnected call to setup fragments, set them up here.
	        if(mService.isConnected() && (listFragment == null || chatFragment == null))
	        	setupFragments();
	        
	        // We never receive the connecting message. Show dialog.
	        if(mService.getConnectionState() == MumbleService.CONNECTION_STATE_CONNECTING)
    			onConnecting();
		}

		/**
		 * Called when service disconnects.
		 * Propagate any error information, and return to the server selection screen.
		 */
		@Override
		public void onServiceDisconnected(ComponentName name) {
			Intent returnIntent = new Intent(); // Return any error data from service
			returnIntent.putExtra(EXTRA_SERVER, mService.getConnectedServer());
			if(mService.getDisconnectReason() != null) {
				Log.d(Globals.LOG_TAG, "Disconnect reason: "+mService.getDisconnectReason());
				returnIntent.putExtra(EXTRA_DISCONNECT_TYPE, mService.getDisconnectReason().ordinal());
				if(mService.getDisconnectReason() == DisconnectReason.Generic) {
					returnIntent.putExtra(EXTRA_GENERIC_REASON, mService.getGenericDisconnectReason());
				} else if(mService.getDisconnectReason() == DisconnectReason.Kick) {
					UserRemove kickReason = mService.getKickReason();
					returnIntent.putExtra(EXTRA_KICK_REASON, kickReason.getReason());
					returnIntent.putExtra(EXTRA_KICK_ACTOR, kickReason.getActor());
				} else if(mService.getDisconnectReason() == DisconnectReason.Reject) {
					Reject rejectReason = mService.getRejectReason();
					returnIntent.putExtra(EXTRA_REJECT_REASON, rejectReason.getReason());
					returnIntent.putExtra(EXTRA_REJECT_TYPE, rejectReason.getType().ordinal());
				}
				setResult(RESULT_OK, returnIntent);
			} else {
				setResult(RESULT_CANCELED, returnIntent);
			}
			
			finish();
		}
	};
	
    /**
     * The {@link android.support.v4.view.PagerAdapter} that will provide fragments for each of the
     * sections. We use a {@link android.support.v4.app.FragmentPagerAdapter} derivative, which will
     * keep every loaded fragment in memory. If this becomes too memory intensive, it may be best
     * to switch to a {@link android.support.v4.app.FragmentStatePagerAdapter}.
     */
    SectionsPagerAdapter mSectionsPagerAdapter;

    /**
     * The {@link ViewPager} that will host the section contents.
     */
    private ViewPager mViewPager;
    
    // Tabs
    private View channelsIndicator;
    private View chatIndicator;
    
    // Favourites
    private MenuItem searchItem;
    private MenuItem mutedButton;
    private MenuItem deafenedButton;
    
    // User control
    private MenuItem userRegisterItem;
    private MenuItem userCommentItem;
    private MenuItem userInformationItem;
	
	private User chatTarget;

	private ProgressDialog mProgressDialog;
	private Button mTalkButton;
	private View pttView;
	
	// Fragments
	private ChannelListFragment listFragment;
	private ChannelChatFragment chatFragment;
	
	// Fragments (split view exclusive)
	private View leftSplit;
	private View rightSplit;
	
	// Proximity sensor
	private WakeLock proximityLock;
	private Settings settings;
	
	public final DialogInterface.OnClickListener onDisconnectConfirm = new DialogInterface.OnClickListener() {
		@Override
		public void onClick(final DialogInterface dialog, final int which) {
			disconnect();
		}
	};
	
    @Override
    public void onCreate(Bundle savedInstanceState) {    	
		settings = Settings.getInstance(this);
		settings.addObserver(this);
		
		// Use theme from settings
		int theme = 0;
		if(settings.getTheme().equals(Settings.ARRAY_THEME_LIGHTDARK)) {
			theme = R.style.Theme_Sherlock_Light_DarkActionBar;
		} else if(settings.getTheme().equals(Settings.ARRAY_THEME_DARK)) {
			theme = R.style.Theme_Sherlock;
		}
		setTheme(theme);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel);
        
        // Handle differences in CallMode
        
        String callMode = settings.getCallMode();
        
        if(callMode.equals(Settings.ARRAY_CALL_MODE_SPEAKER)) {
    		setVolumeControlStream(AudioManager.STREAM_MUSIC);
        } else if(callMode.equals(Settings.ARRAY_CALL_MODE_VOICE)) {
        	setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        }
    	
    	// Set up proximity sensor
    	PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
    	proximityLock = powerManager.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, Globals.LOG_TAG);
        
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_CALL);
        
        // Set up PTT button.
    	
    	mTalkButton = (Button) findViewById(R.id.pushtotalk);
    	pttView = findViewById(R.id.pushtotalk_view);
    	mTalkButton.setOnTouchListener(new OnTouchListener() {
    		
    		private static final int TOGGLE_INTERVAL = 250; // 250ms is the interval needed to toggle push to talk.
    		private long lastTouch = 0;
			
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				if(mService == null) {
					return false;
				}
				
				if(event.getAction() == MotionEvent.ACTION_DOWN && !settings.isPushToTalkToggle()) {
					setPushToTalk(true);
				} else if(event.getAction() == MotionEvent.ACTION_UP) {
					if(settings.isPushToTalkToggle())
						setPushToTalk(!mService.isRecording());
					else {
						if(System.currentTimeMillis()-lastTouch <= TOGGLE_INTERVAL) {
							// Do nothing. We leave the push to talk on, as it has toggled.
						} else {
							setPushToTalk(false);
							lastTouch = System.currentTimeMillis();
						}
					}
				}
				
				return true; // We return true so that the selector that changes the background does not fire.
			}
		});
    	
        updatePTTConfiguration();
        
        mViewPager = (ViewPager) findViewById(R.id.pager);
        
        if(savedInstanceState != null) {
			chatTarget = (User) savedInstanceState.getParcelable(SAVED_STATE_CHAT_TARGET);
        }
    }
    
    // Settings observer
    @Override
    public void update(Observable observable, Object data) {
    	if(data == Settings.OBSERVER_KEY_ALL) {
        	updatePTTConfiguration(); // Update push-to-talk
    	}
    }
    
    private void updatePTTConfiguration() {
    	pttView.setVisibility(settings.isPushToTalk() && settings.isPushToTalkButtonShown() ? View.VISIBLE : View.GONE);
    }
    
    public void setPushToTalk(final boolean talking) {
    	if(mService.isRecording() != talking)
        	mService.setPushToTalk(talking);
    	
    	int pushToTalkBackground = mViewPager != null ? R.color.push_to_talk_background : 0; // Use special 'second action bar' look for background of paged.
    	
    	if(pttView != null)
    		pttView.setBackgroundResource(talking ? R.color.holo_blue_light : pushToTalkBackground);
    }
    
    /* (non-Javadoc)
     * @see android.support.v4.app.FragmentActivity#onSaveInstanceState(android.os.Bundle)
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
    	super.onSaveInstanceState(outState);
		
		if(chatTarget != null)
			outState.putParcelable(SAVED_STATE_CHAT_TARGET, chatTarget);
    }
    
    /* (non-Javadoc)
     * @see com.morlunk.mumbleclient.app.ConnectedActivity#onResume()
     */
    @Override
    protected void onResume() {
    	super.onResume();

    	// Bind to service
    	Intent serviceIntent = new Intent(this, MumbleService.class);
		bindService(serviceIntent, conn, 0);
		
    	if(settings.getCallMode().equals(Settings.ARRAY_CALL_MODE_VOICE))
    		setProximityEnabled(true);
    		
    	
        if(mService != null && mService.getCurrentUser() != null)
        	updateMuteDeafenMenuItems(mService.isMuted(), mService.isDeafened());
        
        // Clear chat notifications when activity is re-opened
        if(mService != null && settings.isChatNotifyEnabled()) {
        	mService.setActivityVisible(true);
        	mService.clearChatNotification();
        }
    }
    
    @Override
    protected void onPause() {
    	super.onPause();
    	
    	if(settings.getCallMode().equals(Settings.ARRAY_CALL_MODE_VOICE))
    		setProximityEnabled(false);
    	
    	if(mService != null) {
        	mService.setActivityVisible(false);
        	
        	// Turn off push to talk when rotating so it doesn't get stuck on, except if it's in toggled state.
        	//if(settings.isPushToTalk() && !mTalkToggleBox.isChecked()) {
        	//	mService.setRecording(false);
        	//}

        	// Unbind to service
    		mService.unregisterObserver(mObserver);
    		unbindService(conn);
    	}
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
    	// Only show favourites and access tokens (DB-related) if the connected server has a DB representation (non-public).
    	MenuItem fullscreenItem = menu.findItem(R.id.menu_fullscreen);
    	MenuItem favouritesViewItem = menu.findItem(R.id.menu_view_favorites_button);
    	MenuItem accessTokensItem = menu.findItem(R.id.menu_access_tokens_button);
    	
    	userRegisterItem = menu.findItem(R.id.menu_user_register);
    	userCommentItem = menu.findItem(R.id.menu_user_comment);
    	userInformationItem = menu.findItem(R.id.menu_user_information);
    	
    	if(mService != null &&
    			mService.getConnectedServer() != null) {
    		
    		favouritesViewItem.setVisible(!mService.isConnectedServerPublic());
    		accessTokensItem.setVisible(!mService.isConnectedServerPublic());
    	}
    	
    	fullscreenItem.setVisible(mViewPager == null); // Only show fullscreen option if in tablet mode
    	
    	return super.onPrepareOptionsMenu(menu);
    }
    
    @TargetApi(Build.VERSION_CODES.FROYO) 
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.activity_channel, menu);
        
        searchItem = menu.findItem(R.id.menu_search);
        
        if(VERSION.SDK_INT >= 8) { // SearchManager supported by Froyo+.
            SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
            SearchView searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
            searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        } else {
        	searchItem.setVisible(false);
        }
        
        mutedButton = menu.findItem(R.id.menu_mute_button);
        deafenedButton = menu.findItem(R.id.menu_deafen_button);
        
        if(mService != null &&
        		mService.getCurrentUser() != null) {
        	updateMuteDeafenMenuItems(mService.isMuted(), mService.isDeafened());
        }
        
        return true;
    }
    
	@Override
    protected void onNewIntent(Intent intent) {
    	super.onNewIntent(intent);
    	
		// Join channel selected in search suggestions if present
		if(intent != null &&
				intent.getAction() != null &&
				intent.getAction().equals(Intent.ACTION_SEARCH)) {
			String resultType = (String) intent.getSerializableExtra(SearchManager.EXTRA_DATA_KEY);
			Uri data = intent.getData();
			
			if(resultType.equals(ChannelSearchProvider.INTENT_DATA_CHANNEL)) {
				int channelId = Integer.parseInt(data.getLastPathSegment());
				
				new AsyncTask<Integer, Void, Void>() {
					@Override
					protected Void doInBackground(Integer... params) {
						mService.joinChannel(params[0]);
						return null;
					}
				}.execute(channelId);
				
			} else if(resultType.equals(ChannelSearchProvider.INTENT_DATA_USER)) {
				int session = Integer.parseInt(data.getLastPathSegment());
				User user = mService.getUser(session);
				listFragment.scrollToUser(user);
			}
			
            if(searchItem != null)
            	searchItem.collapseActionView();
		}
    }

	@Override
	public MumbleService getService() {
		return mService;
	}
    
    /**
     * Updates the 'muted' and 'deafened' action bar icons to reflect the audio status.
     */
    private void updateMuteDeafenMenuItems(boolean muted, boolean deafened) {
    	if(mutedButton == null || deafenedButton == null)
    		return;

    	mutedButton.setIcon(!muted ? R.drawable.ic_action_microphone : R.drawable.ic_microphone_muted_strike);
    	deafenedButton.setIcon(!deafened ? R.drawable.ic_action_audio_on : R.drawable.ic_action_audio_muted);
    }
    
    /**
     * Used to control the user settings shown when registered.
     */
    private void updateUserControlMenuItems() {
    	if(mService == null || 
    			getCurrentUser() == null || 
    			userRegisterItem == null || 
    			userCommentItem == null || 
    			userInformationItem == null)
    		return;
    	
		boolean userRegistered = getCurrentUser().isRegistered;
		userRegisterItem.setEnabled(!userRegistered);
		userCommentItem.setEnabled(userRegistered);
		userInformationItem.setEnabled(userRegistered);
    }
    
    /* (non-Javadoc)
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	
    	switch (item.getItemId()) {
		case R.id.menu_mute_button:
			if(!mService.isMuted()) {
				// Switching to muted
				updateMuteDeafenMenuItems(true, mService.isDeafened());
			} else {
				// Switching to unmuted
				updateMuteDeafenMenuItems(false, false);
			}
			mService.setMuted(!mService.isMuted());
			return true;
		case R.id.menu_deafen_button:
			updateMuteDeafenMenuItems(!mService.isDeafened(), !mService.isDeafened());
			mService.setDeafened(!mService.isDeafened());
			return true;
		case R.id.menu_fullscreen_chat:
			rightSplit.setVisibility(rightSplit.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
			leftSplit.setVisibility(View.VISIBLE);
			return true;
		case R.id.menu_fullscreen_channel:
			leftSplit.setVisibility(leftSplit.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
			rightSplit.setVisibility(View.VISIBLE);
			return true;
		case R.id.menu_view_favorites_button:
			showFavouritesDialog();
			return true;
		case R.id.menu_user_register:
			new AsyncTask<Void, Void, Void>() {
				@Override
				protected Void doInBackground(Void... params) {
					mService.registerSelf();
					return null;
				}
				
				protected void onPostExecute(Void result) {
					Toast.makeText(ChannelActivity.this, R.string.registerSelfSuccess, Toast.LENGTH_SHORT).show();
				};
			}.execute();
			return true;
		case R.id.menu_user_comment:
			// TODO
			Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show();
			return true;
		case R.id.menu_user_information:
			// TODO
			Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show();
			return true;
		case R.id.menu_clear_chat:
			mService.clearChat();
			chatFragment.clear();
			return true;
		case R.id.menu_search:
			return false;
		case R.id.menu_access_tokens_button:
			TokenDialogFragment dialogFragment = TokenDialogFragment.newInstance();
			//if(mViewPager != null) {
				// Phone
				//getSupportFragmentManager().beginTransaction().replace(R.id.pager, dialogFragment).commit();
			//} else {
				// Tablet
				dialogFragment.show(getSupportFragmentManager(), "tokens");
			//}
			return true;
		case R.id.menu_amplifier:
			AmplifierDialogFragment amplifierDialogFragment = AmplifierDialogFragment.newInstance();
			amplifierDialogFragment.show(getSupportFragmentManager(), "amplifier");
			return true;
		case R.id.menu_preferences:
			Intent intent = new Intent(this, Preferences.class);
			intent.putExtra(Preferences.EXTRA_CONNECTED, true);
			startActivity(intent);
			return true;
		case R.id.menu_disconnect_item:
			disconnect();
			return true;
		}
    	
    	return super.onOptionsItemSelected(item);
    }
    
    @Override
	public boolean onKeyDown(final int keyCode, final KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
			final AlertDialog.Builder b = new AlertDialog.Builder(this);
			b.setTitle(R.string.disconnect);
			b.setMessage(R.string.disconnectSure);
			b.setPositiveButton(android.R.string.yes, onDisconnectConfirm);
			b.setNegativeButton(android.R.string.no, null);
			b.show();

			return true;
		}
		
		// Push to talk hardware key
		if(settings.isPushToTalk() && 
				keyCode == settings.getPushToTalkKey() && 
				event.getAction() == KeyEvent.ACTION_DOWN) {
			setPushToTalk(true);
			return true;
		}

		return super.onKeyDown(keyCode, event);
	}
    
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
		// Push to talk hardware key
    	if(settings.isPushToTalk() && 
				keyCode == settings.getPushToTalkKey() && 
				event.getAction() == KeyEvent.ACTION_UP) {
			setPushToTalk(false);
			return true;
		}
    	
    	return super.onKeyUp(keyCode, event);
    }

	/**
	 * Retrieves and sends the access tokens for the active server from the database.
	 */
	public void sendAccessTokens() {
		DbAdapter dbAdapter = mService.getDatabaseAdapter();
		AsyncTask<DbAdapter, Void, Void> accessTask = new AsyncTask<DbAdapter, Void, Void>() {

			@Override
			protected Void doInBackground(DbAdapter... params) {
				DbAdapter adapter = params[0];
				List<String> tokens = adapter.fetchAllTokens(mService.getConnectedServer().getId());
				mService.sendAccessTokens(tokens);
				return null;
			}
		};
		accessTask.execute(dbAdapter);
	}
	
	/**
	 * Sends the passed access tokens to the server.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void updateAccessTokens(List<String> tokens) {
		AsyncTask<List<String>, Void, Void> accessTask = new AsyncTask<List<String>, Void, Void>() {

			@Override
			protected Void doInBackground(List<String>... params) {
				List<String> tokens = params[0];
				mService.sendAccessTokens(tokens);
				return null;
			}
		};
		accessTask.execute(tokens);
	}

	@Override
	public List<String> getTokens() {
		return mService.getDatabaseAdapter().fetchAllTokens(mService.getConnectedServer().getId());
	}

	@Override
	public void addToken(String string) {
		mService.getDatabaseAdapter().createToken(mService.getConnectedServer().getId(), string);
	}

	@Override
	public void deleteToken(String string) {
		mService.getDatabaseAdapter().deleteToken(mService.getConnectedServer().getId(), string);
	};
	
	/**
	 * Sets up the channel and chat fragments.
	 */
	private void setupFragments() {
        if(listFragment == null)
        	listFragment = new ChannelListFragment();
	    if(chatFragment == null)
	    	chatFragment = new ChannelChatFragment();
	    
		if(mViewPager != null) {
            // Create the adapter that will return a fragment for each of the three primary sections
            // of the app.
            mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
            // Set up the ViewPager with the sections adapter.
            mViewPager.setOnPageChangeListener(new OnPageChangeListener() {
				
				@Override
				public void onPageSelected(int arg0) {
					// Hide keyboard if moving to channel list.
					if(arg0 == 0) {
						InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			            imm.hideSoftInputFromWindow(mViewPager.getApplicationWindowToken(), 0);
					}
					// Update indicator
					channelsIndicator.setVisibility(arg0 == 0 ? View.VISIBLE : View.INVISIBLE);
					chatIndicator.setVisibility(arg0 == 1 ? View.VISIBLE : View.INVISIBLE);
				}
				
				@Override
				public void onPageScrolled(int arg0, float arg1, int arg2) { }
				
				@Override
				public void onPageScrollStateChanged(int arg0) { }
			});
           
            mViewPager.setAdapter(mSectionsPagerAdapter);
            
            // Set up tabs
            getSupportActionBar().setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM);
            
            View channelTabView = getLayoutInflater().inflate(R.layout.channel_tab_view, null);
            channelsIndicator = channelTabView.findViewById(R.id.tab_channels_indicator);
            chatIndicator = channelTabView.findViewById(R.id.tab_chat_indicator);
            
            ImageButton channelsButton = (ImageButton) channelTabView.findViewById(R.id.tab_channels);
            ImageButton chatButton = (ImageButton) channelTabView.findViewById(R.id.tab_chat);
            channelsButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					mViewPager.setCurrentItem(0, true);
				}
			});
            chatButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					mViewPager.setCurrentItem(1, true);
				}
			});
            
            getSupportActionBar().setCustomView(channelTabView);
            
        } else {
        	// Otherwise, create tablet UI.
        	listFragment = (ChannelListFragment) getSupportFragmentManager().findFragmentById(R.id.list_fragment);
        	chatFragment = (ChannelChatFragment) getSupportFragmentManager().findFragmentById(R.id.chat_fragment);
        	
        	listFragment.onServiceBound();
        	chatFragment.onServiceBound();
        	
	        leftSplit = findViewById(R.id.left_split);
	        rightSplit = findViewById(R.id.right_split);
        }
	}
    
    /**
	 * Handles activity initialization when the Service has connected.
	 *
	 * Should be called when there is a reason to believe that the connection
	 * might have became valid. The connection MUST be established but other
	 * validity criteria may still be unfilled such as server synchronization
	 * being complete.
	 *
	 * The method implements the logic required for making sure that the
	 * Connected service is in such a state that it fills all the connection
	 * criteria for ChannelList.
	 *
	 * The method also takes care of making sure that its initialization code
	 * is executed only once so calling it several times doesn't cause problems.
	 */
    
	protected void onConnected() {
		// We are now connected! \o/
		
		// If view pager is present, configure phone UI.
        setupFragments();
		
		if (mProgressDialog != null) {
			mProgressDialog.dismiss();
			mProgressDialog = null;
		}
		
		// Tell the service that we are now visible.
        mService.setActivityVisible(true);
        
        // Update user control
        updateUserControlMenuItems();
		
		// Send access tokens after connection.
		sendAccessTokens();
		
		// Restore push to talk state, if toggled. Otherwise make sure it's turned off.
		if(settings.isPushToTalk() && 
				mService.isRecording()) {
			if(settings.isPushToTalkToggle() && settings.isPushToTalkButtonShown())
				setPushToTalk(true);
			else
				mService.setPushToTalk(false);
		}
		
		if(settings.isPushToTalk() &&
				mService.isRecording())

		if(chatTarget != null) {
			listFragment.setChatTarget(chatTarget);
			chatFragment.setChatTarget(chatTarget);
		}
	}

	/**
	 * Handles activity initialization when the Service is connecting.
	 */
	protected void onConnecting() {
		showProgressDialog(R.string.connectionProgressConnectingMessage);
	}
	
	protected void onSynchronizing() {
		showProgressDialog(R.string.connectionProgressSynchronizingMessage);
	}
	
	protected void disconnect() {
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				mService.disconnect();
				return null;
			}
		}.execute();
	}
	
	private void showProgressDialog(final int message) {
		if (mProgressDialog == null) {
			mProgressDialog = ProgressDialog.show(
				ChannelActivity.this,
				getString(R.string.connectionProgressTitle),
				getString(message),
				true,
				true,
				new OnCancelListener() {
					@Override
					public void onCancel(final DialogInterface dialog) {
						mProgressDialog.setMessage(getString(R.string.connectionProgressDisconnectingMessage));
						disconnect();
					}
				});
		} else {
			mProgressDialog.setMessage(getString(message));
		}
	}
	
	/**
	 * @see http://stackoverflow.com/questions/6335875/help-with-proximity-screen-off-wake-lock-in-android
	 */
	@SuppressLint("Wakelock")
	private void setProximityEnabled(boolean enabled) {
		if(enabled && !proximityLock.isHeld()) {
			proximityLock.acquire();
		} else if(!enabled && proximityLock.isHeld()) {
			try {
				Class<?> lockClass = proximityLock.getClass();
				Method release = lockClass.getMethod("release", int.class);
				release.invoke(proximityLock, 1);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void showFavouritesDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		
		builder.setTitle(R.string.favorites);
		
		List<CharSequence> items = new ArrayList<CharSequence>();
		final List<Favourite> activeFavourites = new ArrayList<Favourite>(mService.getFavourites());
		
		for(Favourite favourite : mService.getFavourites()) {
			int channelId = favourite.getChannelId();
			Channel channel = findChannelById(channelId);
			
			if(channel != null) {
				items.add(channel.name);
			} else {
				// TODO remove the favourite from DB here if channel is not found.
				activeFavourites.remove(favourite);
			}
		}
		
		if(items.size() > 0) {
			builder.setItems(items.toArray(new CharSequence[items.size()]), new OnClickListener() {
				
				@Override
				public void onClick(DialogInterface dialog, int which) {
					Favourite favourite = activeFavourites.get(which);
					final Channel channel = findChannelById(favourite.getChannelId());
					
					new AsyncTask<Channel, Void, Void>() {
						
						@Override
						protected Void doInBackground(Channel... params) {
							mService.joinChannel(params[0].id);
							return null;
						}
					}.execute(channel);
				}
			});
		} else {
			builder.setMessage(R.string.noFavorites);
		}
		
		builder.setNegativeButton(android.R.string.cancel, null);
		
		builder.show();
	}
	
	/**
	 * Looks through the list of channels and returns a channel with the passed ID. Returns null if not found.
	 */
	public Channel findChannelById(int channelId) {
		List<Channel> channels = mService.getChannelList();
		for(Channel channel : channels) {
			if(channel.id == channelId) {
				return channel;
			}
		}
		return null;
	}
	
	@Override
	public User getCurrentUser() {
		return mService.getCurrentUser();
	}
	
	@Override
	public User getUserWithIdentifier(int id) {
		for(User user : mService.getUserList()) {
			if(user.session == id) {
				return user;
			}
		}
		return null;
	}
	
	/**
	 * Updates the chat with latest messages from the service.
	 */
	public void updateChat() {
		for(String message : mService.getUnreadChatMessages()) {
			chatFragment.addChatMessage(message);
		}
		mService.clearUnreadChatMessages();
	}
	
	/* (non-Javadoc)
	 * @see com.morlunk.mumbleclient.app.ChannelProvider#sendChannelMessage(java.lang.String)
	 */
	@Override
	public void sendChannelMessage(String message) {
		mService.sendChannelTextMessage(
				message, getCurrentUser().getChannel());
	}
	
	@Override
	public void sendUserMessage(String string, User chatTarget) {
		mService.sendUserTextMessage(string, chatTarget);
	}

	@Override
	public void setChatTarget(User chatTarget) {
		this.chatTarget = chatTarget;
		chatFragment.setChatTarget(chatTarget);
		
		if(mViewPager != null && chatTarget != null)
			mViewPager.setCurrentItem(1, true); // Scroll over to chat view if targeting a new user
	}
	
	/**
	 * @param reason 
	 * @param valueOf
	 */
	private void permissionDenied(String reason, DenyType denyType) {
		Toast.makeText(getApplicationContext(), R.string.permDenied, Toast.LENGTH_SHORT).show();
	}
	
    /**
     * A {@link FragmentPagerAdapter} that returns a fragment corresponding to one of the primary
     * sections of the app.
     */
    public class SectionsPagerAdapter extends FragmentStatePagerAdapter {
    	
        public SectionsPagerAdapter(FragmentManager fragmentManager) {
            super(fragmentManager);
        }

        @Override
        public SherlockFragment getItem(int i) {
        	switch (i) {
			case 0:
				return listFragment;
			case 1:
				return chatFragment;
			default:
				return null;
			}
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0: return getString(R.string.title_section1).toUpperCase(Locale.getDefault());
                case 1: return getString(R.string.title_section2).toUpperCase(Locale.getDefault());
            }
            return null;
        }
        
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
        }
    }

    class ChannelServiceObserver extends BaseServiceObserver {
    	
    	@Override
    	public void onConnectionStateChanged(int state) throws RemoteException {
    		switch (state) {
    		case MumbleService.CONNECTION_STATE_CONNECTING:
    			Log.i(Globals.LOG_TAG, String.format(
    				"%s: Connecting",
    				getClass().getName()));
    			onConnecting();
    			break;
    		case MumbleService.CONNECTION_STATE_SYNCHRONIZING:
    			Log.i(Globals.LOG_TAG, String.format(
    				"%s: Synchronizing",
    				getClass().getName()));
    			onSynchronizing();
    			break;
    		case MumbleService.CONNECTION_STATE_CONNECTED:
    			Log.i(Globals.LOG_TAG, String.format(
    				"%s: Connected",
    				getClass().getName()));
    			onConnected();
    			break;
    		case MumbleService.CONNECTION_STATE_DISCONNECTED:
    			Log.i(Globals.LOG_TAG, String.format(
    				"%s: Disconnected",
    				getClass().getName()));
    			break;
    		default:
    			Assert.fail("Unknown connection state");
    		}
    	}
    	
		@Override
		public void onMessageReceived(final Message msg) throws RemoteException {
			updateChat();
		}

		@Override
		public void onMessageSent(final Message msg) throws RemoteException {
			updateChat();
		}
		
		@Override
		public void onCurrentChannelChanged() throws RemoteException {
			if(mService.isConnected()) {
				listFragment.updateChannelList();
				listFragment.scrollToChannel(getCurrentUser().getChannel());
			}
		}
		
		@Override
		public void onChannelAdded(Channel channel) throws RemoteException {
			if(mService.isConnected())
				listFragment.updateChannelList();
		}
		
		@Override
		public void onChannelRemoved(Channel channel) throws RemoteException {
			if(mService.isConnected())
				listFragment.updateChannelList();
		}
		
		@Override
		public void onChannelUpdated(Channel channel) throws RemoteException {
			if(mService.isConnected())
				listFragment.updateChannel(channel);
		}
		
		@Override
		public void onCurrentUserUpdated() throws RemoteException {
			updateMuteDeafenMenuItems(mService.getCurrentUser().selfMuted, mService.getCurrentUser().selfDeafened);
	        updateUserControlMenuItems();
		}

		@Override
		public void onUserAdded(final User user) throws RemoteException {
			if(mService.isConnected())
				listFragment.updateChannelList();
		}

		@Override
		public void onUserRemoved(final User user, UserRemove remove) throws RemoteException {
			listFragment.removeUser(user);
			updateChat();
		}

		@Override
		public void onUserStateUpdated(final User user, final UserState state) throws RemoteException {
			updateChat();
		}
		
		@Override
		public void onUserUpdated(User user) throws RemoteException {
			listFragment.updateUser(user);
		}
		
		@Override
		public void onUserTalkingUpdated(User user) {
			listFragment.updateUserTalking(user);
		}
		
		/* (non-Javadoc)
		 * @see com.morlunk.mumbleclient.service.BaseServiceObserver#onPermissionDenied(int)
		 */
		@Override
		public void onPermissionDenied(String reason, int denyType) throws RemoteException {
			permissionDenied(reason, DenyType.valueOf(denyType));
		}
	}
}
