# Messaging: talking to agents from Telegram and Slack

An agent can have a bot on a messaging application, one per platform: Telegram and Slack. Its users link their
account once, then write to the bot as they would to the agent: the messages go to their cases, the answers come
back in the chat. The code is in `agentos-service`, package `io.whozoss.agentos.messaging`.

A bot is configuration data, like the agent it belongs to: an admin of the namespace gives the agent a bot from
the UI, and the bot answers at once. There is no setting to add to the instance and no restart.

A case started from the chat is an ordinary case: it lists, opens and runs in the AgentOS UI like any other,
and the agent runs as the linked user, with that user's rights and credentials. The chat and the UI are two ways
into the same case, whose history stays the single record of the conversation.

## Giving an agent a bot

On the agent's page (the namespace's settings, Agents, the agent, edit or inspect), section *Messaging*. Only an
admin of the namespace sees the actions.

**Create a bot** (no token to copy). Needs the instance's bot manager (below).

1. *Create a Telegram bot* gives a single-use link to the bot manager. Open it, press *Start*.
2. The manager answers with a button: tap it, check the bot's name and username, and confirm.
3. AgentOS receives the new bot's token from Telegram, gives the bot to the agent, and links your Telegram
   account to it. The page shows the bot as soon as it exists.

**Use an existing bot.** Create a bot with [@BotFather](https://t.me/BotFather) (`/newbot`), and paste its token in
*Use an existing bot*. AgentOS checks the token with Telegram before saving it.

**Slack: connect a Slack app.** On Slack a bot is an app of the workspace. *Connect a Slack app* shows the
manifest of the agent's app (its name, the permissions and settings AgentOS needs):

1. Open [Slack's apps](https://api.slack.com/apps?new_app=1), choose *From a manifest*, pick the workspace and
   paste the manifest.
2. Install the app in the workspace, then copy its *Bot User OAuth Token* (`xoxb-`, *OAuth & Permissions*).
3. In *Basic Information*, *App-Level Tokens*, generate a token with the `connections:write` scope (`xapp-`).
4. Paste both tokens. AgentOS checks them with Slack before saving them.

The app receives by Socket Mode: like Telegram's long polling, the instance opens the connection, so it needs no
public URL. Slack has no managed bots: there is no bot manager for Slack.

Either way the tokens are stored encrypted (the field encryption of credentials) and never returned by the API.
From the same section, an admin turns the bot off and on, or removes it, which also removes the links of its
users.

**Telegram serves one receiver per bot**: two instances running the same bot take turns failing, shown as *Used
elsewhere*. Bots live in the database of an instance, so each environment has its own.

## The bot manager

The bot manager is a bot of the instance that creates the bots of agents for their admins, with Telegram's
[managed bots](https://core.telegram.org/bots/features#managed-bots). A super-admin sets it once, in Settings,
*Messaging*:

1. Create a bot with @BotFather, for example "AgentOS".
2. In BotFather's Mini App, open the bot's settings and turn *Bot Management Mode* on.
3. Paste its token. AgentOS refuses a bot that cannot manage other bots.

The manager can replace the token of the bots it created: it must be the instance's own bot, never a third
party's. Removing it leaves the bots it created running.

## Using a bot

**Linking an account.** In the AgentOS profile, *Messaging* lists the bots of the agents the user can talk to;
*Connect* gives a single-use link that opens the bot. In Telegram, pressing *Start* sends the link's token back. In
Slack, whose links cannot carry it, the link opens the app's chat and the page shows the message to send there
(`link <token>`). The account that sends the token is linked to the user who asked for it. The link works with
that bot only. The agent's page offers the same *Connect my account*.

**Writing.** In the direct chat with the bot, a message goes to the chat's current case; the first one starts a
case with the bot's agent, in the agent's namespace. Start a message with `@name` to address another agent of the
namespace, as in the UI. Other `/word` messages go to the case too, so prompt commands work. Slack keeps `/` for
its own commands: there, the bot's commands start with `!` (`!new`, `!cases`...). The bot's commands:

| Command | Effect |
|---|---|
| `/new` | The next message starts a new case. |
| `/cases` | Lists the user's recent cases of the bot's namespace; choosing one moves the chat to it. |
| `/case` | Shows the current case, with its link. |
| `/help` | Lists the commands. |

A linked user who may not use the agent (not deployed to them, disabled) is told so, and nothing else happens.

**Answers.** What the agent says in reply to a message from the chat goes back to the chat: its messages, its
questions (choices as buttons, an authorization as a link button), warnings and errors, and a typing indicator
while the case runs (Telegram only: Slack shows none for a bot). Tool calls, thinking and sub-cases stay in the UI. The rule is the one of a conversation: an
answer goes where the question came from, so a message typed in the UI is answered in the UI only.

**Configuration plans.** When the config agent proposes a configuration change in a case the chat follows, the bot
sends the plan as its card in the UI shows it, built from the plan the server froze rather than from what the agent
wrote: the summary, each operation with its fields before and after (current values masked as on the card), the
warnings and the expiry. Its buttons are the card's: *Apply* applies the plan at once (Slack asks for a
confirmation first), *Reject* rejects it, *Open in AgentOS* opens the case. The bot then removes the buttons and
reports each operation's outcome, the UI card updates, and the agent's next answer comes back to the chat. Only the
user the plan was proposed to gets the buttons. A plan that needs a secret (an API key, a password) cannot be
applied from a chat, where the secret would stay in the chat's history on the platform's servers: the message sends
the user to the card for it, and *Reject* stays. A click on a plan already applied, rejected, expired or replaced is
told so.

**Voice messages.** A voice message (Telegram's, or a clip recorded in Slack) is transcribed on the AgentOS server,
then taken in as the text it says, marked 🎤: the bot first shows that text in the chat, so that the user sees what
the agent will read, and the case keeps it as a message like any other. An audio file sent as music is
transcribed too. Nothing of the audio leaves the server: whisper.cpp transcribes it there, after ffmpeg has decoded
it (see [Configuration](#configuration)). An instance without a transcription model, a voice message longer than
the limit, or one that could not be transcribed is answered with why. A photo, a file or a sticker is answered with
what the bot reads, instead of being ignored.

**Continuing a case from the UI.** The header of a case shows a *Continue on Telegram* (or *on Slack*) button for
each bot of the case's namespace the user has linked an account to: the bot posts the case's last answer in the direct chat,
which now goes to that case, with the bot's agent. Replying there continues it.

## Configuration

Nothing is needed to run bots. Optional properties:

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agentos.messaging.public-url` | `AGENTOS_PUBLIC_URL` | empty | Base URL of the UI, for the links to a case or to the profile that the bots send. Empty sends no link. |
| `agentos.messaging.link-token-ttl-minutes` | `AGENTOS_MESSAGING_LINK_TOKEN_TTL_MINUTES` | `10` | How long a link to connect an account, or to create a bot, stays valid. |
| `agentos.messaging.telegram.api-base-url` | `AGENTOS_MESSAGING_TELEGRAM_API_BASE_URL` | `https://api.telegram.org` | The Bot API endpoint. |
| `agentos.messaging.telegram.poll-timeout-seconds` | `AGENTOS_MESSAGING_TELEGRAM_POLL_TIMEOUT_SECONDS` | `25` | Long-polling timeout. |
| `agentos.messaging.slack.api-base-url` | `AGENTOS_MESSAGING_SLACK_API_BASE_URL` | `https://slack.com/api` | The Web API endpoint. |

The Telegram bots poll (`getUpdates`) and the Slack apps hold a Socket Mode connection: the instance needs no
public URL and nothing reaches it from the outside, which suits an instance behind an access proxy.

**Transcribing voice messages.** Voice messages are transcribed only once a whisper.cpp model is set. The server
runs two programs, which must be installed where AgentOS runs: `ffmpeg`, which decodes the audio (Ogg Opus from
Telegram, WebM or MP4 from Slack), and `whisper-cli` from [whisper.cpp](https://github.com/ggml-org/whisper.cpp),
with a `ggml-*.bin` model (`base` is fast, `small` or `medium` understand French and technical terms better). They
are server properties, not UI settings, because they are programs the server runs.

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agentos.messaging.transcription.model` | `AGENTOS_MESSAGING_TRANSCRIPTION_MODEL` | empty | Path of the whisper.cpp model. Empty: voice messages are not transcribed, and the bots say so. |
| `agentos.messaging.transcription.whisper-command` | `AGENTOS_MESSAGING_TRANSCRIPTION_WHISPER_COMMAND` | `whisper-cli` | The whisper.cpp program, by path or on the `PATH`. |
| `agentos.messaging.transcription.ffmpeg-command` | `AGENTOS_MESSAGING_TRANSCRIPTION_FFMPEG_COMMAND` | `ffmpeg` | The ffmpeg program. |
| `agentos.messaging.transcription.language` | `AGENTOS_MESSAGING_TRANSCRIPTION_LANGUAGE` | `auto` | The spoken language (`fr`, `en`...), `auto` to detect it. |
| `agentos.messaging.transcription.max-duration-seconds` | `AGENTOS_MESSAGING_TRANSCRIPTION_MAX_DURATION_SECONDS` | `300` | Longer voice messages are refused. |
| `agentos.messaging.transcription.max-bytes` | `AGENTOS_MESSAGING_TRANSCRIPTION_MAX_BYTES` | `20971520` | Larger ones are refused (20 MB, Telegram's own limit for a bot). |
| `agentos.messaging.transcription.timeout-seconds` | `AGENTOS_MESSAGING_TRANSCRIPTION_TIMEOUT_SECONDS` | `120` | A transcription still running then is stopped. |
| `agentos.messaging.transcription.concurrency` | `AGENTOS_MESSAGING_TRANSCRIPTION_CONCURRENCY` | `1` | Transcriptions at once on the server: whisper uses every core it is given. |

The audio comes from a chat: ffmpeg reads it as untrusted input, from the local file only (`-protocol_whitelist
file`) and as an audio container only (`-format_whitelist`), so that a playlist or a concat list sent as a voice
message cannot make it read anything else. The programs run without a shell, with fixed arguments, in a temporary
directory removed afterwards. A Slack app downloads the clips with its bot token, which it sends to
`files.slack.com` only; this needs the `files:read` scope, in the manifest AgentOS gives. An app created before
needs it added (*OAuth & Permissions*), then reinstalled.

## How it works

| Piece | Role |
|---|---|
| `MessagingBot` | A bot AgentOS runs: the bot of an agent in a namespace (role `AGENT`), or the bot manager of a platform (`MANAGER`). Token encrypted at rest. One agent per bot, one bot per agent, platform and namespace, one manager per platform. |
| `MessagingBotService` | Attaches a bot from its token (checked with the platform first), turns it on or off, removes it; sets the bot manager. Publishes `MessagingBotChanged`. |
| `MessagingBotSupervisor` | Runs the bots of the database: all of them when the application is ready, then each one as it is saved or removed, after the change is committed. Keeps their adapter and status in `MessagingBotRegistry`. |
| `MessagingDriver` | One platform: checks a token, starts a bot. `TelegramDriver` runs a `TelegramAdapter` (HTML formatting, splitting, flood waits) fed by a `TelegramUpdatePoller`. `SlackDriver` gives the app manifest and runs a `SlackAdapter` (markdown blocks, Block Kit buttons, rate limits) fed by a `SlackSocket` (Socket Mode, reconnects when Slack refreshes the connection). |
| `MessagingIdentity` | A platform account linked to a user through a bot. Unique per account and bot, and per user and bot, among active rows. |
| `MessagingConversation` | A chat with a bot and the case its next message goes to. The chat moves between cases; the case does not move. |
| `MessagingGateway` | Takes what the bot of an agent receives: links accounts, checks the user may use the agent, runs the commands, reads, starts and feeds cases (with `@agent` when the case would otherwise go to another agent), transcribes voice messages, applies or rejects a plan. All of it through the endpoints the UI calls, below. |
| `VoiceTranscriber` | Turns a voice message into text: `WhisperCppTranscriber` runs ffmpeg then whisper.cpp on the server. The adapter downloads the audio (`getFile` on Telegram, the file's private URL on Slack). |
| `MessagingPlanCards` | A configuration plan as a chat shows it, from the card's view (`ConfigChangeViewDto`), with Apply and Reject; the report of an apply. |
| `MessagingManagedBots` | Takes what the bot manager receives: the creation link, the button that opens Telegram's bot creation, and the new bot, which it attaches and links to its creator. |
| `MessagingDelivery` | Listens to `CaseEventCreatedEvent`, published by `CaseEventServiceImpl.create` for every stored event, and sends the readable ones to the chat the case's current turn came from; the question of a plan goes as its card. |
| `MessagingController`, `MessagingBotController` | The UI's endpoints, contracts `MessagingApi` (using bots) and `MessagingBotApi` (configuring them) in `agentos-sdk` (`api.messaging`). |

**Rights.** No message arrives through an HTTP request of the user: the bot fetches it from Telegram, or Slack
pushes it on the app's socket, with the sender's account id set by the platform. The gateway finds the AgentOS user
that account is linked to, and runs the chat's work with that user authenticated on the thread (`asUser`, an
`AgentOsAuthentication`, as `AgentOsAuthenticationFilter` sets for a request). Everything a chat does then goes
through the endpoints the UI calls, by their `*Api` beans, whose only implementation is the controller's
method-security proxy: `CaseApi` to read, start and feed cases, `ConfigChangeApi` to show, apply or reject a plan,
and, inside an apply, the `*Api` of each entity written. Their `@PreAuthorize` read the authenticated user, and so
do their inline guards, since `UserService.getCurrentUser()` returns the authenticated user when there is one. A
chat can therefore do exactly what its user can do in the UI. The gateway itself only checks what belongs to the
bot: a linked account, an agent the user may use, a case of the bot's namespace. A button carries only the id of
what it acts on (`MessagingActionCodec`); who clicks comes from the platform, and the plan applied is the one the
endpoint reads back from the case.

The origin of a message travels in its session context, under `messaging` (`SessionContextKeys.MESSAGING`):
the platform, which the agent also sees, and the conversation, which tells `MessagingDelivery` where to answer.
A session context comes from the message's client, so a message can only point its case at a chat of its own
author: the answers of a case are never sent to a chat that belongs to someone else. The updates of one chat are
handled in order, different chats in parallel (`SerialLanes`).

The config agent (`AGENTOS_CONFIG`) reads the bots of a namespace (`messaging`) and tells the user how to give an
agent one: attaching a bot is not a proposal, since it takes the user's confirmation in Telegram or a token they
type.

## Limits of this version

- Direct chats only: messages in groups and channels are ignored. In Slack, a reply in a thread of the direct chat
  goes to the chat's case like any message, and the bot answers in the chat, not in the thread.
- Text and voice only: photos, files and stickers are answered with what the bot reads, not read. A photo's
  caption is not read either.
- A voice message is transcribed on the lane of its chat: the chat's next messages wait for it (a few seconds), the
  other chats do not. Transcriptions beyond `concurrency` wait for one to finish.
- The pending links (to connect an account or create a bot) and the origin of the current turn of each case are
  kept in memory: after a restart, a pending link has to be asked for again, and a case answers the chat again from
  its next message. A run in progress at the restart is lost anyway, its runtime being in memory too.
- One long poll per running bot, on its own thread: fine for tens of bots.
- Answers are sent once the agent's message is complete, not as it is written.
- A cost pause is shown in the UI only.
- A plan applied or rejected from the UI keeps its buttons in the chat; a click there is then told the plan is
  already answered.
