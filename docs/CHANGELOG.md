# What changed in each release

Newest first. Huginn and Muninn are released together, with the same version number.

## 0.96

| | |
|---|---|
| **No more pairing requests** | Fixed: a Kids' TV or phone could make PCs, phones and TVs nearby ask to pair over Bluetooth. Google Nearby is gone from Muninn completely, so it no longer leans on Google Play services. Close-by chat goes over Muninn's own Bluetooth and Wi-Fi. Update every TV and phone. |

## 0.95

| | |
|---|---|
| **Lighter on the battery** | A message that's waiting for someone is sent again at 1 hour and 6 hours instead of every 5 minutes for a day. One "delivered" per message, no reconnecting over and over with no signal, no downloading old mail again, far fewer writes, faster checks. Group messages no longer keep the phone fully awake. |
| **One battery choice** | Fastest, Balanced or Extreme battery saver. Even Extreme keeps one light connection, so calls still ring almost at once. The battery report also shows how often the message servers woke the phone. |
| **Smaller downloads** | Muninn.apk is about 6.9 MB (was 11.5) and the Huginn .exe zip about 36 MB (was 43). |
| **Easier for everyone** | Short Settings with everything else under More, plain words on every screen, one Add someone screen that opens on the camera, and dozens of smaller fixes so nothing feels wonky. |
| **A new look** | Sharp corners with a touch of noble Norse and Gothic on a clean, modern layout. Four looks in both apps: Dark, True black, Light and Brushed metal. |
| **Press and hold a person** | On a phone, or right-click on a computer: their role (Family, Work, Folk), what they may do (calls and video, files, seeing when you're active, Always right away), and where the chat sits (pin, move, mute, archive, unread). |
| **Contacts that just work** | Fixed: a chat stuck on "Waiting for…", and Call or Video missing for someone who is really a contact. Meet someone close by and both tap Keep in touch: you're contacts, no code needed. |
| **Group invite links** | Like Discord: pick when a link expires and how many people can use it, see every link, and revoke any of them. Admins decide whether members can make links too. |
| **Move here** | Move yourself to a new phone, tablet or computer over your home Wi-Fi, confirmed with 4 emoji. The old one locks and can come back as a linked device. |
| **Huginn on a big screen** | The right size on 1080p, 1440p and 4K, with Size from 50% to 200% (Ctrl+= and Ctrl+-). A Discord-style layout, dialogs that fit, Contacts on the rail, and your computer's own file picker. Handheld mode is gone: the layout follows the screen, and a controller's buttons show when you press Select. |
| **Lock where your thumb is** | The pattern sits low on the screen by default; move it in Settings › Lock and privacy. |
| **Kids' TV** | Pick the noise removal (None, Standard, Strong or Both), a parent who reset their phone can reconnect (another parent approves, or after a 24-hour wait with every parent warned), "Say it as text" stays on the TV itself, and calls come up over a show on Google TV. |
| **Checked top to bottom** | A full security and privacy review, and its fixes: moves only on your home network, linked devices confirmed with 4 emoji, nearby devices must prove who they are, messages can't be replayed, relays can't tell who talks to whom and get padded messages, a lock that's very slow to guess, Erase everything really erases everything. Nothing outside the apps guards your data. |
| **More private by default** | Link previews are off for new installs. Muninn asks the keyboard not to learn what you type. Update checks don't say which app you use, and you can turn them off. |

## 0.94

| | |
|---|---|
| **Messages on time, without Google** | In the background Muninn now keeps one light connection to the healthiest relay, checked about every 5 minutes and lined up with the phone's other wake-ups, and swaps to another relay at once if it stops answering. It never falls back to checking every 15 minutes or every hour unless you pick that in Settings. |
| **Always right away** | Your Family folder to start with, and anyone you add (Settings › Notifications, or a chat's menu). A low battery, quiet hours and checking less never hold their messages back. During quiet hours everyone else's messages arrive without a sound. |
| **Replies come at once** | For about 5 minutes after you send or get a message, Muninn stays fully connected so the conversation flows. |
| **Catches up instantly** | When you unlock the phone, when it joins a network, and when you open Muninn. |
| **Honest ticks** | "Delivered" means it reached their phone. Until then it says "Waiting for Anna's phone". In groups, the message's menu shows whose phones have it and whose it's waiting for. |
| **No more hourly floor** | A very low battery or a long quiet spell now checks the one connection every 10 minutes instead of dropping to hourly checks. |
| **Unrestricted background use** | The Finish setting up card explains why it helps and asks for it in one tap. |
| **Battery report shows the connection's cost** | Time on the light connection, checks and wake-ups, reconnects, and a rough share of the battery. |
| **Messages disappear after 7 days** | Every chat, 1:1 and group, deletes messages older than 7 days on every phone and computer: text, photos, voice clips, videos, files, polls and previews. Encrypted backups leave them out too. Chats you already have get the limit the first time 0.94 starts, and the chat list says so once. |
| **Family and Work keep longer** | A group's admins pick its type: Standard, Family or Work. Family and Work groups can keep messages up to 30 days, the same for everyone. In a 1:1 chat with someone in your Family or Work folder you can pick up to 30 days; if you two pick differently, the shorter one counts. |
| **Older apps** | Someone on an older version keeps messages until they update; the chat's info says so. Your side still deletes them on time. |
| **Huginn looks and feels like Discord on a PC** | Drag the edges between panels to resize them, hide the list or the member list, compact or cozy messages with their own text size, and Dark, True black or Light themes with any accent color (Settings › Appearance). |
| **Who's around** | A group's member list shows who's online, idle or in a voice room. Click a name for their profile card: Message, Call, Video, a nickname, a note only you see, and their folders. |
| **Voice panel** | In a voice room on a PC, who you're with, Mute, Deafen and Leave sit at the foot of the list. Push to talk: hold a key you choose (Settings › Calls). |
| **Unread and mentions** | Groups and channels show a dot for anything new and a number for @mentions of you. Fold a group's Text or Voice category away. |
| **Keyboard shortcuts** | Ctrl+/ lists them all. |
| **Huginn catches up** | On a PC: albums, video messages, polls, link previews and the Shared page; invite links, introductions, the Family group offer and the 4 matching emoji; files of up to 4 GB over the internet; and Linked devices, both as your phone's companion and as the phone others link to. |
| **Signed updates** | Every download is signed with the project's own key, and both apps refuse an update that isn't. Huginn.exe also gets extra Windows protections (Control Flow Guard, CET). |

## 0.93

| | |
|---|---|
| **Albums and a camera inside the app** | Pick several photos at once and they arrive as one album. Take photos with Muninn's own camera, with a front/back flip, without the picture landing in your gallery. |
| **Video messages** | Record up to a minute, watch it back, then choose Send or Discard. Nothing is sent until you tap Send. |
| **Polls** | Ask a question with up to 10 answers, in a chat or a group, and see who voted for what. |
| **Link previews** | A link shows its title and picture. Your phone makes the preview, so the other person's phone never visits the site. Turn it off under Settings › Privacy. |
| **Shared photos, videos and links** | Every chat has a page with everything that's been shared in it. |
| **Requests in their own list** | People who have your code but aren't contacts yet, introductions and group invites wait under Requests until you say yes. |
| **Easier to add the people you trust** | Invite links that work once, introductions ("Mom wants to introduce you to Grandma", and you both say yes), an offer to add the people in your Family group, tapping phones together, and 4 matching emoji to check it's really them. |
| **Files over the internet** | Send a file of up to 4 GB straight to a contact's phone, wherever they are. Small files can also go in pieces through the free relays. Files never go through our server. |
| **Group video** | Turn on the camera in a voice room with up to 4 people. Video goes straight between phones; anyone who can't connect directly stays on voice. |
| **Linked devices** | Use your chats on a tablet or second phone. It shows your phone's chats and sends through it while your phone is on and online. |
| **Mute, archive and mark as unread** | Hold a chat for the options. Rename people from the chat list too, and give each chat its own color. |
| **Refresh buttons** | Check for new messages, or look for phones nearby again, with one tap. |
| **Kinder to your battery** | Follow Do Not Disturb, check less when nothing has happened for a while, a mobile data saver, and backups and Kids' TV updates only while charging. |
| **Folk** | The third starting folder is now called Folk. |
| **No location prompt** | Muninn no longer asks for location on Android 13 and newer. Older phones are asked only when you turn on Nearby. |

## 0.92

| | |
|---|---|
| **Choose how often to check** | Settings › Notifications › Check for messages: Right away, Every 15 minutes, Every hour, or Only when open. The saving modes keep nothing running while Muninn is closed; calls that come in between show as missed. |
| **Low battery saves more** | Below 30% Muninn checks every 15 minutes, below 15% every hour, and goes back to your choice once it's charging. You can switch this off. |
| **Quiet hours** | Check every hour, or not at all, overnight or whenever you choose. |
| **Lighter in the background** | Bluetooth and Nearby only look for phones close by while Muninn is open (unless you turn on Find phones close by in the background), fewer connections stay open, and Wi-Fi discovery works hardest on the Files tab. |
| **Battery report** | Settings › Notifications shows what Muninn kept running today, how often it checked, and the data it used. |
| **True black** | A pure black background under Display, which saves a little on OLED screens. |
| **Video calls on the speaker** | Video calls start on the speaker unless a Bluetooth headset or headphones are connected, and use less battery when it's low. |

## 0.91

| | |
|---|---|
| **Noise removal switch** | Settings › Calls › Noise removal, in Muninn and in Huginn. Turn it off if people say you sound quiet; echo is still removed. |
| **Send apps to a TV** | Files › Send an app sends an app from your phone (or an .apk file) to a TV with Muninn on the same Wi-Fi. The TV asks before it installs. |
| **Apps for the Kids' TV** | On the TV's page, Apps › Send an app. The TV takes only the app its parent sends, and Android asks on the TV before installing. |
| **Settings in folders** | Settings opens on a short list (Notifications, Privacy, Lock, Calls, Nearby and sharing, Display and accessibility, Backup and erase, About and updates), the same in both apps. |

Muninn 0.91 updates over 0.9 as usual: tap Install when Android asks. Only phones still on 0.8 need the one-time reinstall described under 0.9.

## 0.9

> **Muninn needs reinstalling once.** From 0.9 Muninn is signed with a private key instead of the shared test key, so nobody else can make an update your phone would accept. Android won't install it over 0.8, so on each phone: Settings › **Back up chats**, uninstall Muninn, install Muninn.apk from this release, then **Restore from backup**. A Kids' TV is reinstalled the same way and added again from the parent's phone. Updates after this one install as normal. Huginn updates as usual.

| | |
|---|---|
| **Choose where call sound goes** | During a call or voice room, tap **Audio** to switch between the phone, the speaker, wired headphones or a Bluetooth headset. |
| **Video calls you can see** | The name, connection and timer sit in a strip under the video instead of on top of it, and answering a video call asks for the camera if it's needed. |
| **Tidy your contacts** | Remove a contact to the Trash (kept 30 days, with the chat, so Restore brings everything back), drag contacts to reorder them, and folders show as sections you can collapse. |
| **Nearby, your way** | Nearby always sits at the bottom of the list, and **Visible to people nearby** in Settings hides you from strangers while your contacts still reach you. |
| **Finish setting up** | A card on Muninn's main screen lists any missing permissions with one tap each. |
| **Steam Deck like a PC** | Huginn on a handheld gets the full desktop layout. The right stick moves a mouse pointer (R3 or RB to click, RT to right-click), the left stick scrolls, LB switches section and LT opens options. |
| **Pattern unlock with a controller or remote** | Draw your unlock pattern with the D-pad, a controller or a TV remote. |
| **Kids' TV** | The child hears callers again, the TV is locked to its parents, and more than one parent can manage it. |
| **Clearer calls on the PC** | Huginn now cancels echo and removes background noise, like Muninn. |
| **Safer updates and files** | Every update is checked against its SHA-256 before it installs, the Flatpak only sees your Downloads, Documents, Pictures, Videos and Music folders, and with a password set, Panic lock now locks your keys away too. |

## 0.8

| | |
|---|---|
| **Video calls** | One-to-one video calls between phones and Windows, end-to-end encrypted, with camera switching and a small self view. |
| **Direct calls for family** | People in your Family folder (or anyone you switch on) connect straight to you, so calls and video use no relay data. Everyone else stays on the relay and never sees your IP address. |
| **Smoother calls** | An adaptive buffer, loss repair and higher quality sound; calls ring faster and reconnect when you change networks. |
| **Screen share** | Share your screen from Huginn in a voice room. |
| **Group text and call from the list** | Hold a contact, tick a few more, and start a group text or a group call. Group messages show how many have read them. |
| **Messenger touches** | Reactions, swipe to reply, reply from the notification, edit with "(edited)", emoji picker, typing dots, active status, a photo gallery and search in each chat. |
| **Desktop like it should be** | Huginn on a PC types in a normal box at the bottom of the chat, with Ctrl+K, threads, pins, drag and drop, @mentions and a New messages line. |
| **Kids' TV** | Clearer calls with echo cancelling and noise removal, a microphone control on the TV, full-screen photos, the show pauses for a call and picks back up after, storage and Clear history from the parent's phone, and people who haven't accepted yet show as waiting. |
| **Battery saver** | Muninn uses far less battery with the screen off while still getting messages and calls. Turn it off for maximum speed. |
| **Windows** | No more antivirus false alarms from the updater, a cleaner folder, updates checked before they install, and it runs on Linux through Proton and Wine. |
| **Linux and SteamOS** | The Flatpak is back. |

## 0.7

| | |
|---|---|
| **Faster messages** | Tor is gone. Messages, photos and voice clips go straight to the relays again, still end-to-end encrypted, and arrive right away. |
| **Calls that work anywhere** | Calls over cell service connect quickly and carry sound both ways. |
| **Group calls and voice rooms** | Ring a whole group, or make a channel a voice room people drop in and out of. |
| **Kids' TV calls** | Kids call the people a parent approved, and the TV rings over whatever is playing when a parent calls, even right after it restarts. Message notifications show too. |
| **Louder TV microphone** | The TV's microphone is boosted for voice clips, dictation and calls, with a level slider on the parent's phone. |
| **Invite family to the TV** | From the phone, a parent can invite another adult to talk with the kids on the TV, and add people to family groups. |
| **TV updates itself** | Muninn on the TV checks for updates daily and installs them with the remote, or when a parent taps Update TV. |
| **Share the app** | Someone who scans your code with a regular camera gets a download page, and "Share the app" sends Muninn straight to a nearby phone. |
| **Lock first** | Opening the app never shows a chat before the lock screen, and the recent apps view stays blank. |
| **Rename anywhere** | Rename people from the Contacts tab or by holding a chat. |
| **Tidy Windows folder** | The Windows zip holds just Huginn.exe and a lib folder, and the message sound crash is fixed. |
| **Sharing stays home** | Huginn's Wi-Fi sharing listens only on your home network. |

## 0.6

| | |
|---|---|
| **Group chats and channels** | Groups with their own channels, a member list and admins. Every message is encrypted to each member separately. |
| **Contact folders** | Sort people into Family, Work, Folk or any folder you name. |
| **Encrypted calls** | One-to-one voice calls on both apps, with a call history. |
| **Kids' TV** | Muninn on a Google TV becomes a simple screen for children to reach Mom and Dad: big buttons, quick messages, voice clips and dictation. No sign-in. A parent pairs it once from their phone, edits its quick messages, and is the only one who can unlock it. |
| **Windows** | Huginn as a portable zip: unzip anywhere and run, nothing to install. |
| **New layouts** | Huginn, and Muninn on tablets, use a Discord-style rail of groups, channels and members. |
