package com.morlunk.mumbleclient.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import net.sf.mumble.MumbleProto.Reject.RejectType;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.Log;
import android.util.Xml;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.crittercism.app.Crittercism;
import com.morlunk.mumbleclient.Globals;
import com.morlunk.mumbleclient.R;
import com.morlunk.mumbleclient.app.db.DbAdapter;
import com.morlunk.mumbleclient.app.db.PublicServer;
import com.morlunk.mumbleclient.app.db.Server;
import com.morlunk.mumbleclient.service.MumbleProtocol.DisconnectReason;
import com.morlunk.mumbleclient.service.MumbleService;

/**
 * Called whenever server info is changed.
 * @author morlunk
 *
 */
interface ServerInfoListener {
	public void serverInfoUpdated();
}

interface ServerConnectHandler {
	public void connectToServer(Server server);
	public void connectToPublicServer(PublicServer server);
	public void publicServerFavourited();
	public List<Server> getServers();
}

/**
 * The main server list activity.
 *
 * Shows a list of servers and allows connecting to these. Also provides
 * ways to start creating and editing servers.
 *
 * @author pcgod
 *
 */
public class ServerList extends SherlockFragmentActivity implements ServerInfoListener, ServerConnectHandler {

	public static final int CHANNEL_ACTIVITY_REQUEST = 1;
	
	private ServerListFragment serverListFragment;
	private PublicServerListFragment publicServerListFragment;
	private ViewPager pager;
	
	@Override
	protected final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		boolean debuggable =  ( 0 != ( getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE ) );
		
		if(!debuggable) {
			Crittercism.init(getApplicationContext(), "50650bc62cd95250d3000004");
		} else {
			Log.i(Globals.LOG_TAG, "Crittercism disabled in debug build.");
		}
		
		if (savedInstanceState != null) {
			serverListFragment = (ServerListFragment) getSupportFragmentManager().getFragment(savedInstanceState, ServerListFragment.class.getName());
			publicServerListFragment = (PublicServerListFragment) getSupportFragmentManager().getFragment(savedInstanceState, PublicServerListFragment.class.getName());
		} else {
			serverListFragment = new ServerListFragment();
			publicServerListFragment = new PublicServerListFragment();
		}
				
		setContentView(R.layout.activity_server_list);
		
		pager = (ViewPager) findViewById(R.id.pager);
		ServerListPagerAdapter pagerAdapter = new ServerListPagerAdapter(getSupportFragmentManager());
		pager.setAdapter(pagerAdapter);
		pager.setOffscreenPageLimit(10);
		
		pager.setOnPageChangeListener(new OnPageChangeListener() {
			
			@Override
			public void onPageSelected(int position) {
				switch (position) {
				case 1:
					fillPublicList();
					break;
				}
			}
			
			@Override
			public void onPageScrolled(int arg0, float arg1, int arg2) {
			}
			
			@Override
			public void onPageScrollStateChanged(int arg0) {
			}
		});
		
		if(getIntent().getAction() == Intent.ACTION_VIEW) {
			// Load mumble:// links
			final Uri data = getIntent().getData();
			final Server server = parseServerUri(data); // Create mock server entry from intent data
			
			AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
			alertBuilder.setTitle(R.string.connectToServer);
			
			// Build a string of the server details
			StringBuilder serverInfoBuilder = new StringBuilder();
			serverInfoBuilder.append(getString(R.string.serverHost)+": "+server.getHost()+"\n");
			serverInfoBuilder.append(getString(R.string.serverPort)+": "+server.getPort()+"\n");
			if(!server.getUsername().equals(""))
				serverInfoBuilder.append(getString(R.string.serverUsername)+": "+server.getUsername()+"\n");
			if(!server.getPassword().equals(""))
				serverInfoBuilder.append(getString(R.string.serverPassword)+": "+server.getPassword()+"\n");
			alertBuilder.setMessage(serverInfoBuilder.toString());
			
			// Show alert dialog prompting the user to specify a username if needed.
			final EditText usernameField = new EditText(this);
			if(server.getUsername().equals("")) {
				usernameField.setHint(R.string.serverUsername);
				alertBuilder.setView(usernameField);
			}
			
			alertBuilder.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					if(!usernameField.getText().toString().equals(""))
						server.setUsername(usernameField.getText().toString());
					connectToServer(server);
				}
			});
			
			alertBuilder.show();
		}
	}
	
	@Override
	public final boolean onCreateOptionsMenu(final Menu menu) {
		super.onCreateOptionsMenu(menu);
		getSupportMenuInflater().inflate(R.menu.activity_server_list, menu);
		
		return true;
	}
	
	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_preferences:
			final Intent prefs = new Intent(this, Preferences.class);
			startActivity(prefs);
			return true;
		}
		serverListFragment.onOptionsItemSelected(item);
		publicServerListFragment.onOptionsItemSelected(item);
		return false;
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if(requestCode == CHANNEL_ACTIVITY_REQUEST) {
			if(resultCode == RESULT_OK) {
				// Check to see if we received an error that caused the disconnect, indicate the user if so.
				DisconnectReason reason = DisconnectReason.values()[data.getIntExtra(ChannelActivity.EXTRA_DISCONNECT_TYPE, 0)];
				final Server server = data.getParcelableExtra(ChannelActivity.EXTRA_SERVER);
				AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
				alertBuilder.setTitle(R.string.disconnected);
				alertBuilder.setPositiveButton(R.string.retry, new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						connectToServer(server);
					}
				});
				alertBuilder.setNegativeButton(android.R.string.cancel, new OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				});
				
				switch (reason) {
				case Generic:
					String response = data.getStringExtra(ChannelActivity.EXTRA_GENERIC_REASON);
					alertBuilder.setMessage(response);
					break;

				case Kick:
					String kickReason = data.getStringExtra(ChannelActivity.EXTRA_KICK_REASON);
					alertBuilder.setMessage(getString(R.string.kickedMessage, kickReason));
					break;
				
				case Reject:
					String rejectReason = data.getStringExtra(ChannelActivity.EXTRA_REJECT_REASON);
					RejectType rejectType = RejectType.values()[data.getIntExtra(ChannelActivity.EXTRA_REJECT_TYPE, 0)];
					alertBuilder.setMessage(rejectReason);
					if(rejectType == RejectType.WrongUserPW || rejectType == RejectType.WrongServerPW) {		
						// Allow password entry
						final EditText passwordField = new EditText(this);
						passwordField.setHint(R.string.serverPassword);
						alertBuilder.setView(passwordField);

						alertBuilder.setPositiveButton(R.string.retry, new OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								// Update server in database if it's not public (id = -1)
								if(server.getId() != -1) {
									DbAdapter adapter = new DbAdapter(ServerList.this);
									adapter.open();
									adapter.updateServer(server.getId(), server.getName(), server.getHost(), server.getPort(), server.getUsername(), passwordField.getText().toString());
									adapter.close();
								}
								server.setPassword(passwordField.getText().toString());

								// Reconnect
								connectToServer(server);
							}
						});
					}
					break;
				}
				alertBuilder.show();
			}
		}
	}
	
	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		super.onSaveInstanceState(outState);
		getSupportFragmentManager().putFragment(outState, ServerListFragment.class.getName(), serverListFragment);
		getSupportFragmentManager().putFragment(outState, PublicServerListFragment.class.getName(), publicServerListFragment);
	}

	/**
	 * Starts connecting to a server.
	 *
	 * @param id
	 */
	public void connectToServer(final Server server) {
		final Intent connectionIntent = new Intent(this, MumbleService.class);
		connectionIntent.setAction(MumbleService.ACTION_CONNECT);
		connectionIntent.putExtra(MumbleService.EXTRA_SERVER, server);
		startService(connectionIntent);
		final Intent channelListIntent = new Intent(this, ChannelActivity.class);
		startActivityForResult(channelListIntent, CHANNEL_ACTIVITY_REQUEST);
	}

	/**
	 * Starts connecting to a public server.
	 *
	 * @param id
	 */
	public void connectToPublicServer(final PublicServer server) {
		
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		
		// Allow username entry
		final EditText usernameField = new EditText(this);
		usernameField.setHint(R.string.serverUsername);
		alertBuilder.setView(usernameField);

		alertBuilder.setTitle(R.string.connectToServer);
		
		alertBuilder.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				PublicServer newServer = server;
				newServer.setUsername(usernameField.getText().toString());
				Intent connectionIntent = new Intent(ServerList.this, MumbleService.class);
				connectionIntent.setAction(MumbleService.ACTION_CONNECT);
				connectionIntent.putExtra(MumbleService.EXTRA_SERVER, server);
				startService(connectionIntent);
				final Intent channelListIntent = new Intent(ServerList.this, ChannelActivity.class);
				startActivityForResult(channelListIntent, CHANNEL_ACTIVITY_REQUEST);
			}
		});
		
		alertBuilder.show();
	}
	
	private Server parseServerUri(Uri data) {
		String host = data.getHost();
		String userInfo = data.getUserInfo();
		
		// Password and username can be in format user:pass@example.com. Deal with this.
		String username = "";
		String password = "";
		if(userInfo != null && !userInfo.equals("")) {
			if(userInfo.split(":").length == 2) {
				String[] userInfoArray = userInfo.split(":");
				username = userInfoArray[0];
				password = userInfoArray[1];
			} else {
				username = userInfo;
			}
		}
		
		int port = data.getPort();
		if(port == -1)
			port = 64738;
		
		Server server = new Server(-1, "", host, port, username, password);
		return server;
	}
	
	public List<Server> getServers() {
		DbAdapter dbAdapter = new DbAdapter(this);
		dbAdapter.open();
		List<Server> servers = dbAdapter.fetchAllServers();
		dbAdapter.close();
		return servers;
	}
	
	private void fillPublicList() {
		if(!publicServerListFragment.isFilled()) {
			new PublicServerFetchTask() {
				protected void onPostExecute(List<PublicServer> result) {
					super.onPostExecute(result);
					
					if(result == null) {
						// Handle error
						Toast.makeText(ServerList.this, R.string.error_fetching_servers, Toast.LENGTH_SHORT).show();
						return;
					}
					
					if(publicServerListFragment.isVisible())
						publicServerListFragment.setServers(result);
				};
			}.execute();
		}
	}

	@Override
	public void serverInfoUpdated() {
		serverListFragment.updateServers();
	}
	
	public void publicServerFavourited() {
		pager.setCurrentItem(0, true);
		serverListFragment.updateServers();
	};
	
	private class ServerListPagerAdapter extends FragmentPagerAdapter {

		public ServerListPagerAdapter(FragmentManager fm) {
			super(fm);
		}

		@Override
		public int getCount() {
			return 2;
		}

		@Override
		public Fragment getItem(int arg0) {
			switch (arg0) {
			case 0:
				return serverListFragment;
			case 1:
				return publicServerListFragment;
			default:
				return null;
			}
		}
		
		@Override
		public CharSequence getPageTitle(int position) {
			switch (position) {
			case 0:
				return getString(R.string.server_list_title_favorite);
			case 1:
				return getString(R.string.server_list_title_public_internet);
			}
			return null;
		}
		
		@Override
		public void destroyItem(ViewGroup container, int position, Object object) {
			// Override to do nothing.
		}
	}
	
	private class PublicServerFetchTask extends AsyncTask<Void, Void, List<PublicServer>> {
		
		private static final String MUMBLE_PUBLIC_URL = "http://www.mumble.info/list2.cgi";
		
		@Override
		protected List<PublicServer> doInBackground(Void... params) {
			try {
				// Fetch XML from server
				URL url = new URL(MUMBLE_PUBLIC_URL);
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				connection.setRequestMethod("GET");
				connection.addRequestProperty("version", Globals.PROTOCOL_VERSION_STRING);
				connection.connect();
				InputStream stream = connection.getInputStream();				
				
				XmlPullParser parser = Xml.newPullParser();
				parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
				parser.setInput(stream, "UTF-8");
				parser.nextTag();
				
				List<PublicServer> serverList = new ArrayList<PublicServer>();
				
				parser.require(XmlPullParser.START_TAG, null, "servers");
				while(parser.next() != XmlPullParser.END_TAG) {
			        if (parser.getEventType() != XmlPullParser.START_TAG) {
			            continue;
			        }
			        
			        serverList.add(readEntry(parser));
				}
				parser.require(XmlPullParser.END_TAG, null, "servers");
				
				return serverList;
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}
		
		private PublicServer readEntry(XmlPullParser parser) throws XmlPullParserException, IOException {			
			String name = parser.getAttributeValue(null, "name");
			String ca = parser.getAttributeValue(null, "ca");
			String continentCode = parser.getAttributeValue(null, "continent_code");
			String country = parser.getAttributeValue(null, "country");
			String countryCode = parser.getAttributeValue(null, "country_code");
			String ip = parser.getAttributeValue(null, "ip");
			String port = parser.getAttributeValue(null, "port");
			String region = parser.getAttributeValue(null, "region");
			String url = parser.getAttributeValue(null, "url");
			
			parser.nextTag();
			
			PublicServer server = new PublicServer(name, ca, continentCode, country, countryCode, ip, Integer.parseInt(port), region, url);
			
			return server;
		}
		
	}
}
