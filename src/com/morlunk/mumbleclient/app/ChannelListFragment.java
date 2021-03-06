package com.morlunk.mumbleclient.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.mumble.MumbleProto.RequestBlob;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.TranslateAnimation;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockFragment;
import com.google.protobuf.ByteString;
import com.morlunk.mumbleclient.R;
import com.morlunk.mumbleclient.app.db.DbAdapter;
import com.morlunk.mumbleclient.app.db.Favourite;
import com.morlunk.mumbleclient.service.BaseServiceObserver;
import com.morlunk.mumbleclient.service.MumbleProtocol.MessageType;
import com.morlunk.mumbleclient.service.MumbleService;
import com.morlunk.mumbleclient.service.audio.AudioOutputHost;
import com.morlunk.mumbleclient.service.model.Channel;
import com.morlunk.mumbleclient.service.model.User;
import com.morlunk.mumbleclient.view.PlumbleNestedAdapter;
import com.morlunk.mumbleclient.view.PlumbleNestedListView;
import com.morlunk.mumbleclient.view.PlumbleNestedListView.OnNestedChildClickListener;
import com.morlunk.mumbleclient.view.PlumbleNestedListView.OnNestedGroupClickListener;

public class ChannelListFragment extends SherlockFragment implements OnNestedChildClickListener, OnNestedGroupClickListener {

	/**
	 * The parent activity MUST implement ChannelProvider. An exception will be
	 * thrown otherwise.
	 */
	private ChannelProvider channelProvider;

	private PlumbleNestedListView channelUsersList;
	private UserListAdapter usersAdapter;

	private User chatTarget;
	
	public void updateChannelList() {
		usersAdapter.updateChannelList();
		usersAdapter.notifyDataSetChanged();
	}

	/**
	 * Updates the user specified in the users adapter.
	 * 
	 * @param user
	 */
	public void updateUser(User user) {
		usersAdapter.refreshUser(user);
	}
	
	public void updateChannel(Channel channel) {
		if(channel.description != null || channel.descriptionHash != null) {
			usersAdapter.commentsSeen.put(channel, usersAdapter.dbAdapter.isCommentSeen(
				channel.name,
				channel.descriptionHash != null ? channel.descriptionHash.toStringUtf8() : channel.description));
		}
		updateChannelList();
	}

	public void updateUserTalking(User user) {
		usersAdapter.refreshTalkingState(user);
	}

	/**
	 * Removes the user from the channel list.
	 * 
	 * @param user
	 */
	public void removeUser(User user) {
		usersAdapter.notifyDataSetChanged();
	}
	
	/**
	 * Scrolls to the passed channel.
	 */
	public void scrollToChannel(Channel channel) {
		int channelPosition = usersAdapter.channels.indexOf(channel);
		int flatPosition = usersAdapter.getFlatGroupPosition(channelPosition);
		if(flatPosition-channelUsersList.getFirstVisiblePosition() > channelUsersList.getChildCount())
			channelUsersList.setSelection(flatPosition);
	}
	/**
	 * Scrolls to the passed user.
	 */
	public void scrollToUser(User user) {
		int userPosition = usersAdapter.channelMap.get(user.getChannel().id).indexOf(user);
		int channelPosition = usersAdapter.channels.indexOf(user.getChannel());
		int flatPosition = usersAdapter.getFlatChildPosition(channelPosition, userPosition);
		if(flatPosition-channelUsersList.getFirstVisiblePosition() > channelUsersList.getChildCount())
			channelUsersList.setSelection(flatPosition);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * android.support.v4.app.Fragment#onCreateView(android.view.LayoutInflater,
	 * android.view.ViewGroup, android.os.Bundle)
	 */
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.channel_list, container, false);

		// Get the UI views
		channelUsersList = (PlumbleNestedListView) view
				.findViewById(R.id.channelUsers);
		channelUsersList.setOnChildClickListener(this);
		channelUsersList.setOnGroupClickListener(this);
		
		return view;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.support.v4.app.Fragment#onAttach(android.app.Activity)
	 */
	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);

		try {
			channelProvider = (ChannelProvider) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException(activity.toString()
					+ " must implement ChannelProvider!");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.support.v4.app.Fragment#onActivityCreated(android.os.Bundle)
	 */
	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		
		// If service is bound, update. Otherwise, we should receive a request to do so once bound from activity.
		if(channelProvider.getService() != null)
			onServiceBound();
		
		registerForContextMenu(channelUsersList);
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		// Update channel list when resuming.
		if(usersAdapter != null && 
				channelProvider.getService() != null && 
				channelProvider.getService().isConnected())
	        updateChannelList();
	}
	
	public void onServiceBound() {
		usersAdapter = new UserListAdapter(getActivity(),
				channelProvider.getService());
		channelUsersList.setAdapter(usersAdapter);
        updateChannelList();
        scrollToChannel(channelProvider.getService().getCurrentChannel());
	}

	public void setChatTarget(User chatTarget) {
		User oldTarget = chatTarget;
		this.chatTarget = chatTarget;
		if (usersAdapter != null) {
			if (oldTarget != null)
				usersAdapter.refreshUser(oldTarget);
			usersAdapter.refreshUser(chatTarget);
		}
	}
	
	@Override
	public void onNestedChildClick(AdapterView<?> parent, View view,
			int groupPosition, int childPosition, long id) {
		View flagsView = view.findViewById(R.id.userFlags);
		User user = (User) usersAdapter.getChild(groupPosition, childPosition);
		boolean expand = !usersAdapter.selectedUsers.contains(user);
		if (expand)
			usersAdapter.selectedUsers.add(user);
		else
			usersAdapter.selectedUsers.remove(user);
		usersAdapter.expandPane(expand, flagsView, true);
		
	}
	
	@Override
	public void onNestedGroupClick(AdapterView<?> parent, View view,
			int groupPosition, long id) {
		View pane = view.findViewById(R.id.channel_row_pane);
		Channel channel = (Channel) usersAdapter.getGroup(groupPosition);
		boolean expand = !usersAdapter.selectedChannels.contains(channel);
		if(expand)
			usersAdapter.selectedChannels.add(channel);
		else
			usersAdapter.selectedChannels.remove(channel);
		usersAdapter.expandPane(expand, pane, true);
	}
	
	class UserListAdapter extends PlumbleNestedAdapter {
		private final MumbleService service;
		private final DbAdapter dbAdapter;
		private List<Channel> channels = new ArrayList<Channel>();
		@SuppressLint("UseSparseArrays") // Don't like 'em
		private Map<Integer, List<User>> channelMap = new HashMap<Integer, List<User>>();
		/**
		 * A list of the selected users. Used to restore the expanded state after reloading the adapter.
		 */
		private List<User> selectedUsers = new ArrayList<User>();
		/**
		 * A list of the selected channels. Used to restore the expanded state after reloading the adapter.
		 */
		private List<Channel> selectedChannels = new ArrayList<Channel>();

		private final Map<Object, Boolean> commentsSeen = new HashMap<Object, Boolean>();

		public UserListAdapter(final Context context,
				final MumbleService service) {
			super(context);
			this.service = service;
			this.dbAdapter = this.service.getDatabaseAdapter();
		}

		/**
		 * Fetches a new list of channels from the service.
		 */
		public void updateChannelList() {
			this.channels = service.getSortedChannelList();
			this.channelMap = service.getChannelMap();
		}

		public void refreshUser(User user) {
			if(!service.getUserList().contains(user))
				return;
			
			int channelPosition = channels.indexOf(user.getChannel());
			/*
			if (!channelUsersList.isGroupExpanded(channelPosition))
				return;
			*/
			int userPosition = channelMap.get(user.getChannel().id).indexOf(user);
			int position = usersAdapter.getFlatChildPosition(channelPosition, userPosition);
			
			View userView = channelUsersList.getChildAt(position
					- channelUsersList.getFirstVisiblePosition());

			// Update comment state
			if (user.comment != null
					|| user.commentHash != null
					&& !service.isConnectedServerPublic()) {
				commentsSeen.put(user, dbAdapter.isCommentSeen(
						user.name,
						user.commentHash != null ? user.commentHash
								.toStringUtf8() : user.comment));
			}

			if (userView != null && userView.isShown() && userView.getTag() != null && userView.getTag().equals(user))
				refreshElements(userView, user);
		}

		public void refreshTalkingState(User user) {
			if(!service.getUserList().contains(user))
				return;
			
			int channelPosition = channels.indexOf(user.getChannel());
			/*
			if (!channelUsersList.isGroupExpanded(channelPosition))
				return;
			*/
			int userPosition = channelMap.get(user.getChannel().id).indexOf(user);
			int position = usersAdapter.getFlatChildPosition(channelPosition, userPosition);
			View userView = channelUsersList.getChildAt(position
					- channelUsersList.getFirstVisiblePosition());

			if (userView != null && userView.isShown() && userView.getTag() != null && userView.getTag().equals(user)
					&& service.getUserList().contains(user))
				refreshTalkingState(userView, user);

		}

		private void refreshElements(final View view, final User user) {
			final View titleView = view.findViewById(R.id.channel_user_row_title);
			final TextView name = (TextView) view
					.findViewById(R.id.userRowName);
			final TextView comment = (TextView) view.findViewById(R.id.channel_user_row_comment);
			final TextView localMute = (TextView) view.findViewById(R.id.channel_user_row_mute);
			final TextView chat = (TextView) view.findViewById(R.id.channel_user_row_chat);
			final TextView registered = (TextView) view.findViewById(R.id.channel_user_row_registered);
			final TextView closeView = (TextView) view.findViewById(R.id.channel_user_row_close);
			final View flagsView = view.findViewById(R.id.userFlags);
			//final ImageView info = (ImageView) view.findViewById(R.id.channel_user_row_info);
			
			name.setText(user.name);
			name.setTypeface(null, user.equals(service.getCurrentUser()) ? Typeface.BOLD : Typeface.NORMAL);

			refreshTalkingState(view, user);

			int chatImage = chatTarget != null && chatTarget.equals(user) ? R.drawable.ic_action_chat_active : R.drawable.ic_action_chat_dark;
			chat.setCompoundDrawablesWithIntrinsicBounds(0, chatImage, 0, 0);
			chat.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					User oldUser = chatTarget;
					boolean activated = chatTarget == null || !chatTarget.equals(user);
					chatTarget = activated ? user : null;
					channelProvider.setChatTarget(chatTarget);
					int image = activated ? R.drawable.ic_action_chat_active : R.drawable.ic_action_chat_dark;
					chat.setCompoundDrawablesWithIntrinsicBounds(0, image, 0, 0);
					if(oldUser != null)
						refreshUser(oldUser); // Update chat icon of old user when changing targets
				}
			});
			
			localMute.setText(user.localMuted ? R.string.channel_user_row_muted : R.string.channel_user_row_mute);
			int muteImage = user.localMuted ? R.drawable.ic_action_audio_muted_active : R.drawable.ic_action_audio_muted;
			localMute.setCompoundDrawablesWithIntrinsicBounds(0, muteImage, 0, 0);
			localMute.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					user.localMuted = !user.localMuted;
					localMute.setText(user.localMuted ? R.string.channel_user_row_muted : R.string.channel_user_row_mute);
					int image = user.localMuted ? R.drawable.ic_action_audio_muted_active : R.drawable.ic_action_audio_muted;
					localMute.setCompoundDrawablesWithIntrinsicBounds(0, image, 0, 0);
				}
			});

			if (!commentsSeen.containsKey(user)) {
				String commentData = user.commentHash != null ? user.commentHash
						.toStringUtf8() : user.comment;
				commentsSeen.put(
						user,
						commentData != null ? dbAdapter.isCommentSeen(
								user.name, commentData) : false);
			}

			int commentImage = commentsSeen.get(user) ? R.drawable.ic_action_comment
					: R.drawable.ic_action_comment_active;
			comment.setCompoundDrawablesWithIntrinsicBounds(0, commentImage, 0, 0);
			comment.setVisibility(user.comment != null
					|| user.commentHash != null ? View.VISIBLE : View.GONE);
			comment.setOnClickListener(new OnCommentClickListener(user));
			
			/*
			info.setOnClickListener(new OnClickListener() {
				key
				@Override
				public void onClick(View v) {
					// TODO Auto-generated method stub
					
				}
			});
			*/
			
			closeView.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					selectedUsers.remove(user);
					expandPane(false, flagsView, true);
				}
			});
			
			registered.setVisibility(user.isRegistered ? View.VISIBLE : View.GONE);

			Channel channel = user.getChannel();
			DisplayMetrics metrics = getResources().getDisplayMetrics();
			
			// Pad the view depending on channel's nested level.
			float margin = (getNestedLevel(channel) + 1)
					* TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
							25, metrics);
			titleView.setPadding((int) margin, titleView.getPaddingTop(),
					titleView.getPaddingRight(), titleView.getPaddingBottom());
		}

		private void refreshTalkingState(final View view, final User user) {
			final ImageView state = (ImageView) view
					.findViewById(R.id.userRowState);

			if (user.selfDeafened) {
				state.setImageResource(R.drawable.ic_deafened);
			} else if (user.selfMuted) {
				state.setImageResource(R.drawable.ic_muted);
			} else if (user.serverDeafened) {
				state.setImageResource(R.drawable.ic_server_deafened);
			} else if (user.serverMuted) {
				state.setImageResource(R.drawable.ic_server_muted);
			} else if (user.suppressed) {
				state.setImageResource(R.drawable.ic_suppressed);
			} else {
				if (user.talkingState == AudioOutputHost.STATE_TALKING) {
					state.setImageResource(R.drawable.ic_talking_on);
				} else {
					state.setImageResource(R.drawable.ic_talking_off);
				}
			}
		}

		public int getNestedLevel(Channel channel) {
			if (channel.parent != -1) {
				for (Channel c : channels) {
					if (c.id == channel.parent) {
						return 1 + getNestedLevel(c);
					}
				}
			}
			return 0;
		}

		@Override
		public Object getChild(int groupPosition, int childPosition) {
			Channel channel = channels.get(groupPosition);
			List<User> channelUsers = channelMap.get(channel.id);
			return channelUsers.get(childPosition);
		}
		
		@Override
		public View getChildView(int groupPosition, int childPosition,
				int depth, View v, ViewGroup arg4) {
			if (v == null) {
				final LayoutInflater inflater = (LayoutInflater) getContext()
						.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				v = inflater.inflate(R.layout.channel_user_row, null);
			}

			User user = (User) getChild(groupPosition, childPosition);
			
			View flagsView = v.findViewById(R.id.userFlags);
			expandPane(selectedUsers.contains(user), flagsView, false);

			refreshElements(v, user);
			v.setTag(user);

			return v;
		}

		@Override
		public int getChildCount(int arg0) {
			return channelMap.get(channels.get(arg0).id).size();
		}

		@Override
		public Object getGroup(int arg0) {
			return channels.get(arg0);
		}

		@Override
		public int getGroupCount() {
			return channels.size();
		}
		
		private void expandPane(final Boolean expand, final View pane, boolean animated) {
			if(animated) {
				int from = expand ? pane.getLayoutParams().height : 0;
				int to = expand ? 0 : pane.getLayoutParams().height;
				TranslateAnimation translateAnimation = new TranslateAnimation(0, 0, from, to);
				translateAnimation.setDuration(200);
				translateAnimation.setAnimationListener(new AnimationListener() {
					@Override
					public void onAnimationStart(Animation animation) {
						pane.setVisibility(View.VISIBLE);
					}
					
					@Override
					public void onAnimationRepeat(Animation animation) { }
					
					@Override
					public void onAnimationEnd(Animation animation) {
						pane.setVisibility(expand ? View.VISIBLE : View.GONE);
					}
				});
				pane.startAnimation(translateAnimation);
			} else {
				pane.setVisibility(expand ? View.VISIBLE : View.GONE);
			}
		}

		@Override
		public View getGroupView(final int groupPosition, int depth,
				View convertView, ViewGroup parent) {
			View v = convertView;
			if (v == null) {
				final LayoutInflater inflater = (LayoutInflater) getContext()
						.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				v = inflater.inflate(R.layout.channel_row, null);
			}
			final Channel channel = channels.get(groupPosition);
			
			final View pane = v.findViewById(R.id.channel_row_pane);
			expandPane(selectedChannels.contains(channel), pane, false);
			
			ImageView expandView = (ImageView) v.findViewById(R.id.channel_row_expand);
			expandView.setImageResource(isGroupExpanded(groupPosition) ? R.drawable.ic_action_minus_light : R.drawable.ic_action_add_light);
			expandView.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					if(isGroupExpanded(groupPosition))
						collapseGroup(groupPosition);
					else
						expandGroup(groupPosition);
					notifyVisibleSetChanged();
				}
			});

			TextView nameView = (TextView) v
					.findViewById(R.id.channel_row_name);
			TextView countView = (TextView) v.findViewById(R.id.channel_row_count);
			
			// FIXME add back channel count

			nameView.setText(channel.name);
			countView.setText(String.format("%d", channel.userCount));
			countView.setTextColor(getResources().getColor(channel.userCount > 0 ? R.color.holo_blue_light : android.R.color.darker_gray));
			
			Favourite favourite = service.getFavouriteForChannel(channel);

			final TextView favouriteView = (TextView) v.findViewById(R.id.channel_row_favourite);
			final TextView joinView = (TextView) v.findViewById(R.id.channel_row_join);
			final TextView commentView = (TextView) v.findViewById(R.id.channel_row_comment);
			final TextView closeView = (TextView) v.findViewById(R.id.channel_row_close);
			
			joinView.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					new AsyncTask<Void, Void, Void>() {
						
						protected void onPreExecute() {
							selectedChannels.remove(channel);
							expandPane(false, pane, true);
						};
						
						@Override
						protected Void doInBackground(Void... params) {
							service.joinChannel(channel.id);
							return null;
						}
						
					}.execute();
				}
			});
			
			/*
			int chatImage = chatTarget != null && chatTarget.equals(channel) ? R.drawable.ic_action_chat_active : R.drawable.ic_action_chat_dark;
			chatView.setCompoundDrawablesWithIntrinsicBounds(0, chatImage, 0, 0);
			chatView.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					User oldUser = selectedUser;
					boolean activated = selectedUser == null || !selectedUser.equals(user);
					selectedUser = activated ? user : null;
					channelProvider.setChatTarget(selectedUser);
					chatImage.setImageResource(activated ? R.drawable.ic_action_chat_active : R.drawable.ic_action_chat_dark);
					if(oldUser != null)
						refreshUser(oldUser); // Update chat icon of old user when changing targets
				}
			});
			*/

			int favouriteImage = favourite != null ? R.drawable.ic_action_favourite_on : R.drawable.ic_action_favourite_off;
			favouriteView.setCompoundDrawablesWithIntrinsicBounds(0, favouriteImage, 0, 0);
			favouriteView.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					new AsyncTask<Channel, Void, Boolean>() {

						@Override
						protected Boolean doInBackground(Channel... params) {
							Channel favouriteChannel = params[0];
							Favourite f = service.getFavouriteForChannel(favouriteChannel);
							if(f == null)
								dbAdapter.createFavourite(service.getConnectedServer().getId(), channel.id);
							else
								dbAdapter.deleteFavourite(f.getId());
							return f == null; // True: created, False: deleted
						}
						
						protected void onPostExecute(Boolean result) {
							int image = result ? R.drawable.ic_action_favourite_on : R.drawable.ic_action_favourite_off;
							favouriteView.setCompoundDrawablesWithIntrinsicBounds(0, image, 0, 0);
							service.updateFavourites();
						};
						
					}.execute(channel);
				}
			});

			// Update comment state
			commentView.setVisibility(channel.description != null
					|| channel.descriptionHash != null ? View.VISIBLE : View.GONE);
			if(channel.description != null || channel.descriptionHash != null) {
				if (!commentsSeen.containsKey(channel)) {
					commentsSeen.put(channel, dbAdapter.isCommentSeen(
							channel.name,
							channel.descriptionHash != null ? channel.descriptionHash
									.toStringUtf8() : channel.description));
				}

				int commentImage = commentsSeen.get(channel) ? R.drawable.ic_action_comment
						: R.drawable.ic_action_comment_active;
				commentView.setCompoundDrawablesWithIntrinsicBounds(0, commentImage, 0, 0);
				commentView.setOnClickListener(new OnCommentClickListener(channel));
			}
			
			closeView.setOnClickListener(new OnClickListener() {
				
				@Override
				public void onClick(View v) {
					selectedChannels.remove(channel);
					expandPane(false, pane, true);
				}
			});
			
			View channelTitle = v.findViewById(R.id.channel_row_title);
			
			// Pad the view depending on channel's nested level.
			DisplayMetrics metrics = getResources().getDisplayMetrics();
			float margin = getNestedLevel(channel)
					* TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
							25, metrics);
			channelTitle.setPadding((int) margin, channelTitle.getPaddingTop(), channelTitle.getPaddingRight(),
					channelTitle.getPaddingBottom());

			return v;
		}

		@Override
		public int getGroupDepth(int groupPosition) {
			Channel channel = (Channel) getGroup(groupPosition);
			return getNestedLevel(channel);
		}
		
		@Override
		public int getGroupParentPosition(int groupPosition) {
			Channel channel = channels.get(groupPosition);
			for(int x=0;x<channels.size();x++) {
				Channel c = channels.get(x);
				if(c.id == channel.parent)
					return x;
			}
			return -1;
		}
		
		@Override
		public boolean isGroupExpandedByDefault(int groupPosition) {
			Channel channel = channels.get(groupPosition);
			return channel.userCount > 0;
		}
		
		private class OnCommentClickListener implements OnClickListener {

			private User user;
			private Channel channel;
			
			public OnCommentClickListener(User user) {
				this.user = user;
			}
			
			public OnCommentClickListener(Channel channel) {
				this.channel = channel;
			}

			@SuppressLint("NewApi")
			@Override
			public void onClick(View v) {
				String name = null;
				String comment = null;
				ByteString commentHash = null;
				TextView commentView = null;
				if(user != null) {
					commentView = (TextView) v.findViewById(R.id.channel_user_row_comment);
					name = user.name;
					comment = user.comment;
					commentHash = user.commentHash;
					
					commentsSeen.put(user, true);
				} else if(channel != null) {
					commentView = (TextView) v.findViewById(R.id.channel_row_comment);
					name = channel.name;
					comment = channel.description;
					commentHash = channel.descriptionHash;
					
					commentsSeen.put(channel, true);
				}
				
				commentView.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_action_comment, 0, 0);
				if (channelProvider.getService() != null
						&& !channelProvider.getService()
								.isConnectedServerPublic()) {
					dbAdapter.setCommentSeen(
							name,
							commentHash != null ? commentHash
									.toStringUtf8() : comment);
				}

				AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
				builder.setTitle(R.string.comment);
				builder.setPositiveButton(R.string.close, null);
				final WebView webView = new WebView(getContext());
				final StringBuilder sb = new StringBuilder();
				sb.append("<center>");
				sb.append(getResources().getString(R.string.retrieving));
				sb.append("</center>");
				String string = sb.toString();
				webView.loadDataWithBaseURL("", string, "text/html", "utf-8",
						"");
				builder.setView(webView);

				final AlertDialog dialog = builder.show();

				if (comment != null) {
					webView.loadDataWithBaseURL("", comment, "text/html",
							"utf-8", "");
				} else if (commentHash != null) {
					BaseServiceObserver serviceObserver = new BaseServiceObserver() {
						
						@Override
						public void onUserUpdated(User user) throws RemoteException {
							if(user != null && 
									user.equals(OnCommentClickListener.this.user) && 
									user.comment != null &&
									dialog.isShowing()) {
								webView.loadDataWithBaseURL("", user.comment,
										"text/html", "utf-8", "");
								channelProvider.getService().unregisterObserver(this);
							}
						}
						
						@Override
						public void onChannelUpdated(Channel channel)
								throws RemoteException {
							if(channel != null && 
									channel.equals(OnCommentClickListener.this.channel) && 
									channel.description != null &&
									dialog.isShowing()) {
								webView.loadDataWithBaseURL("", channel.description,
										"text/html", "utf-8", "");
								channelProvider.getService().unregisterObserver(this);
							}
						}
					};
					channelProvider.getService().registerObserver(serviceObserver);
					
					// Retrieve comment from blob
					final RequestBlob.Builder blobBuilder = RequestBlob
							.newBuilder();
					if(user != null)
						blobBuilder.addSessionComment(user.session);
					else if (channel != null)
						blobBuilder.addChannelDescription(channel.id);

					new AsyncTask<Void, Void, Void>() {
						@Override
						protected Void doInBackground(Void... params) {
							channelProvider.getService().sendTcpMessage(MessageType.RequestBlob, blobBuilder);
							return null;
						};
					}.execute();
				}
			}
		}
	}
}
