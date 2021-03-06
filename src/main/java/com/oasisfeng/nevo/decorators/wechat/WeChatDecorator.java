/*
 * Copyright (C) 2015 The Nevolution Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oasisfeng.nevo.decorators.wechat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.Log;

import com.oasisfeng.nevo.sdk.MutableNotification;
import com.oasisfeng.nevo.sdk.MutableStatusBarNotification;
import com.oasisfeng.nevo.sdk.NevoDecoratorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.MessagingStyle;

import static android.app.Notification.EXTRA_TITLE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;
import static android.os.Build.VERSION_CODES.O;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;

/**
 * Bring state-of-art notification experience to WeChat.
 *
 * Created by Oasis on 2015/6/1.
 */
public class WeChatDecorator extends NevoDecoratorService {

	public static final String WECHAT_PACKAGE = "com.tencent.mm";
	private static final int MAX_NUM_ARCHIVED = 20;
	private static final long GROUP_CHAT_SORT_KEY_SHIFT = 24 * 60 * 60 * 1000L;			// Sort group chat like one day older message.
	private static final String CHANNEL_MESSAGE = "message_channel_new_id";				// Channel ID used by WeChat for all message notifications
	private static final String OLD_CHANNEL_MESSAGE = "message";						//   old name for migration
	private static final String CHANNEL_MISC = "reminder_channel_id";					// Channel ID used by WeChat for misc. notifications
	private static final String OLD_CHANNEL_MISC = "misc";								//   old name for migration
	private static final String CHANNEL_DND = "message_dnd_mode_channel_id";			// Channel ID used by WeChat for its own DND mode
	private static final String CHANNEL_GROUP_CONVERSATION = "group";					// WeChat has no separate group for group conversation

	private static final @ColorInt int PRIMARY_COLOR = 0xFF33B332;
	private static final @ColorInt int LIGHT_COLOR = 0xFF00FF00;
	static final String SENDER_MESSAGE_SEPARATOR = ": ";
	private static final String KEY_SILENT_REVIVAL = "nevo.wechat.revival";

	@Override public void apply(final MutableStatusBarNotification evolving) {
		final MutableNotification n = evolving.getNotification();
		final Bundle extras = n.extras;

		CharSequence title = extras.getCharSequence(EXTRA_TITLE);
		if (title == null || title.length() == 0) {
			Log.e(TAG, "Title is missing: " + evolving);
			return;
		}
		if (title != (title = EmojiTranslator.translate(title))) extras.putCharSequence(EXTRA_TITLE, title);

		final int original_id = evolving.getOriginalId();
		if (BuildConfig.DEBUG) extras.putString("nevo.debug", "ID:" + original_id + ",t:" + n.tickerText);

		n.color = PRIMARY_COLOR;        // Tint the small icon

		final String channel_id = SDK_INT >= O ? n.getChannelId() : null;
		if (CHANNEL_MISC.equals(channel_id) || n.tickerText == null) {
			if (SDK_INT >= O && channel_id == null) n.setChannelId(CHANNEL_MISC);
			Log.d(TAG, "Skip further process for non-conversation notification: " + title);	// E.g. web login confirmation notification.
			return;
		}

		// WeChat previously uses dynamic counter starting from 4097 as notification ID, which is reused after cancelled by WeChat itself,
		//   causing conversation duplicate or overwritten notifications.
		final String pkg = evolving.getPackageName();
		if (! isDistinctId(n, pkg, original_id))
			evolving.setId(title.hashCode());	// Don't use the hash code of original title, which might have already evolved.

		extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
		if (BuildConfig.DEBUG) n.flags &= ~ Notification.FLAG_LOCAL_ONLY;

		final CharSequence content_text = extras.getCharSequence(Notification.EXTRA_TEXT);
		final boolean group_chat = isGroupChat(n.tickerText, title.toString(), content_text);
		n.setSortKey(String.valueOf(Long.MAX_VALUE - n.when + (group_chat ? GROUP_CHAT_SORT_KEY_SHIFT : 0)));    // Place group chat below other messages
		if (SDK_INT >= O) {
			if (extras.containsKey(KEY_SILENT_REVIVAL)) {
				n.setGroup("nevo.group.auto");	// Special group name to let Nevolution auto-group it as if not yet grouped. (To be standardized in SDK)
				n.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);		// This trick makes notification silent
			}
			if (group_chat && ! CHANNEL_DND.equals(channel_id)) n.setChannelId(CHANNEL_GROUP_CONVERSATION);
			else if (channel_id == null) n.setChannelId(CHANNEL_MESSAGE);		// WeChat versions targeting O+ have its own channel for message
		}

		MessagingStyle messaging = mMessagingBuilder.buildFromExtender(evolving, title, group_chat);
		if (messaging == null)	// EXTRA_TEXT will be written in buildFromArchive()
			messaging = mMessagingBuilder.buildFromArchive(n, title, group_chat, getArchivedNotifications(evolving.getOriginalKey(), MAX_NUM_ARCHIVED));
		if (messaging == null) return;
		final List<MessagingStyle.Message> messages = messaging.getMessages();
		if (messages.isEmpty()) return;

		if (group_chat) messaging.setGroupConversation(true).setConversationTitle(title);
		final Bundle addition = new Bundle();
		messaging.addCompatExtras(addition);
		for (final String key : addition.keySet()) {    // Copy the extras generated by MessagingStyle to notification extras.
			final Object value = addition.get(key);
			if (value == null) continue;
			if (value instanceof CharSequence) extras.putCharSequence(key, (CharSequence) value);
			else if (value instanceof Parcelable[]) extras.putParcelableArray(key, (Parcelable[]) value);
			else if (value instanceof Bundle) extras.putBundle(key, (Bundle) value);
			else if (value instanceof Boolean) extras.putBoolean(key, (Boolean) value);
			else Log.e(TAG, "Unsupported extra \"" + key + "\": " + value);
		}
		extras.putCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE, title);
		extras.putString(Notification.EXTRA_TEMPLATE, TEMPLATE_MESSAGING);

		if (SDK_INT >= N && extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY) != null)
			n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;		// No more alert for direct-replied notification.
	}

	private boolean isDistinctId(final Notification n, final String pkg, final int id) {
		if (mDistinctIdSupported != null) {
			if (mDistinctIdSupported) return true;
			if (id > 4096 && id <= 4100) return false;		// If not in the legacy ID range, check version code again in case WeChat is upgraded.
		}
		int version = 0;
		final ApplicationInfo app_info = n.extras.getParcelable("android.appInfo");
		if (app_info != null) try {
			if (pkg.equals(app_info.packageName))	// This will be Nevolution for active evolved notifications.
				//noinspection JavaReflectionMemberAccess
				version = (int) ApplicationInfo.class.getField("versionCode").get(app_info);
		} catch (final IllegalAccessException | NoSuchFieldException | ClassCastException ignored) {}    // Fall-through
		if (version == 0) try {
			version = getPackageManager().getPackageInfo(pkg, 0).versionCode;
		} catch (final PackageManager.NameNotFoundException ignored) {}
		return version != 0 && (mDistinctIdSupported = version >= 1340);	// Distinct ID is supported since WeChat 6.7.3.
	}
	private Boolean mDistinctIdSupported;

	// [Direct message with 1 unread]	Ticker: "Oasis: Hello",		Title: "Oasis",	Content: "Hello"
	// [Direct message with >1 unread]	Ticker: "Oasis: Hello",		Title: "Oasis",	Content: "[2]Oasis: Hello"
	// [Service message with 1 unread]	Ticker: "FedEx: Delivered",	Title: "FedEx",	Content: "[Link] Delivered"
	// [Group chat with 1 unread]		Ticker: "Oasis: Hello",		Title: "Group",	Content: "Oasis: Hello"
	// [Group chat with >1 unread]		Ticker: "Oasis: [Link] Mm",	Title: "Group",	Content: "[2]Oasis: [Link] Mm"
	private static boolean isGroupChat(final CharSequence ticker_text, final String title, final CharSequence content_text) {
		if (content_text == null) return false;
		final String ticker = ticker_text.toString().trim();	// Ticker text (may contain trailing spaces) always starts with sender (same as title for direct message, but not for group chat).
		final String content = content_text.toString();			// Content text includes sender for group and service messages, but not for direct messages.
		final int pos = content.indexOf(ticker.substring(0, Math.min(10, ticker.length())));    // Seek for the first 10 chars of ticker in content.
		if (pos >= 0 && pos <= 6) {        // Max length (up to 999 unread): [999t]
			final String message = pos > 0 && content.charAt(0) == '[' ? content.substring(pos) : content;    // Content without unread count prefix
			return ! message.startsWith(title + SENDER_MESSAGE_SEPARATOR);    // If positive, most probably a direct message with more than 1 unread
		} else return false;                                        // Most probably a direct message with 1 unread
	}

	@Override protected void onNotificationRemoved(final String key, final int reason) {
		if (reason == REASON_APP_CANCEL) {		// Only if "Removal-Aware" of Nevolution is activated
			Log.d(TAG, "Cancel notification: " + key);
			cancelNotification(key);	// Will cancel all notifications evolved from this original key, thus trigger the "else" branch below
		} else if (reason == REASON_CHANNEL_BANNED) {	// In case WeChat deleted our notification channel for group conversation in Insider delivery mode
			mHandler.post(() -> reviveNotificationAfterChannelDeletion(key));
		} else if (SDK_INT < O || reason == REASON_CANCEL) {	// Exclude the removal request by us in above case. (Removal-Aware is only supported on Android 8+)
			mMessagingBuilder.markRead(key);
		}
	}

	private void reviveNotificationAfterChannelDeletion(final String key) {
		Log.d(TAG, ("Revive silently: ") + key);
		final Bundle addition = new Bundle();
		addition.putBoolean(KEY_SILENT_REVIVAL, true);
		recastNotification(key, addition);
	}

	@Override protected void onConnected() {
		if (SDK_INT >= O) {
			mWeChatTargetingO = isWeChatTargeting26OrAbove();
			final List<NotificationChannel> channels = new ArrayList<>();
			channels.add(makeChannel(CHANNEL_GROUP_CONVERSATION, R.string.channel_group_message, false));
			// WeChat versions targeting O+ have its own channels for message and misc
			channels.add(migrate(OLD_CHANNEL_MESSAGE,	CHANNEL_MESSAGE,	R.string.channel_message, false));
			channels.add(migrate(OLD_CHANNEL_MISC,		CHANNEL_MISC,		R.string.channel_misc, true));
			createNotificationChannels(WECHAT_PACKAGE, channels);
		}
	}

	@RequiresApi(O) private NotificationChannel migrate(final String old_id, final String new_id, final @StringRes int new_name, final boolean silent) {
		final NotificationChannel channel_message = getNotificationChannel(WECHAT_PACKAGE, old_id);
		deleteNotificationChannel(WECHAT_PACKAGE, old_id);
		if (channel_message != null) return cloneChannel(channel_message, new_id, new_name);
		else return makeChannel(new_id, new_name, silent);
	}

	@RequiresApi(O) private NotificationChannel makeChannel(final String channel_id, final @StringRes int name, final boolean silent) {
		final NotificationChannel channel = new NotificationChannel(channel_id, getString(name), NotificationManager.IMPORTANCE_HIGH/* Allow heads-up (by default) */);
		if (silent) channel.setSound(null, null);
		else channel.setSound(getDefaultSound(), new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
		channel.enableLights(true);
		channel.setLightColor(LIGHT_COLOR);
		return channel;
	}

	@RequiresApi(O) private NotificationChannel cloneChannel(final NotificationChannel channel, final String id, final int new_name) {
		final NotificationChannel clone = new NotificationChannel(id, getString(new_name), channel.getImportance());
		clone.setGroup(channel.getGroup());
		clone.setDescription(channel.getDescription());
		clone.setLockscreenVisibility(channel.getLockscreenVisibility());
		clone.setSound(Optional.ofNullable(channel.getSound()).orElse(getDefaultSound()), channel.getAudioAttributes());
		clone.setBypassDnd(channel.canBypassDnd());
		clone.setLightColor(channel.getLightColor());
		clone.setShowBadge(channel.canShowBadge());
		clone.setVibrationPattern(channel.getVibrationPattern());
		return clone;
	}

	@Nullable private Uri getDefaultSound() {	// Before targeting O, WeChat actually plays sound by itself (not via Notification).
		return mWeChatTargetingO ? Settings.System.DEFAULT_NOTIFICATION_URI : null;
	}

	private boolean isWeChatTargeting26OrAbove() {
		try {
			return getPackageManager().getApplicationInfo(WECHAT_PACKAGE, PackageManager.GET_UNINSTALLED_PACKAGES).targetSdkVersion >= O;
		} catch (final PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	@Override public void onCreate() {
		super.onCreate();
		mMessagingBuilder = new MessagingBuilder(this, this::recastNotification);
	}

	@Override public void onDestroy() {
		mMessagingBuilder.close();
		super.onDestroy();
	}

	private MessagingBuilder mMessagingBuilder;
	private boolean mWeChatTargetingO;
	private final Handler mHandler = new Handler();

	static final String TAG = "Nevo.Decorator[WeChat]";
}
