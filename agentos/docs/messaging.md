# Messaging: talking to agents from Telegram

An agent can have a bot on a messaging application. Its users link their account once, then write to the bot
as they would to the agent: the messages go to their cases, the answers come back in the chat. Telegram is the
first platform; the code is in `agentos-service`, package `io.whozoss.agentos.messaging`.

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

Either way the token is stored encrypted (the field encryption of credentials) and never returned by the API.
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
*Connect* gives a single-use link that opens the bot. Pressing *Start* in Telegram sends the link's token back:
the account that sends it is linked to the user who asked for it. The link works with that bot only. The agent's
page offers the same *Connect my account*.

**Writing.** In the direct chat with the bot, a message goes to the chat's current case; the first one starts a
case with the bot's agent, in the agent's namespace. Start a message with `@name` to address another agent of the
namespace, as in the UI. Other `/word` messages go to the case too, so prompt commands work. The bot's commands:

| Command | Effect |
|---|---|
| `/new` | The next message starts a new case. |
| `/cases` | Lists the user's recent cases of the bot's namespace; choosing one moves the chat to it. |
| `/case` | Shows the current case, with its link. |
| `/help` | Lists the commands. |

A linked user who may not use the agent (not deployed to them, disabled) is told so, and nothing else happens.

**Answers.** What the agent says in reply to a message from the chat goes back to the chat: its messages, its
questions (choices as buttons, an authorization as a link button), warnings and errors, and a typing indicator
while the case runs. Tool calls, thinking and sub-cases stay in the UI. The rule is the one of a conversation: an
answer goes where the question came from, so a message typed in the UI is answered in the UI only.

**Continuing a case from the UI.** The header of a case shows a *Continue on Telegram* button for each bot of the
case's namespace the user has linked an account to: the bot posts the case's last answer in the direct chat,
which now goes to that case, with the bot's agent. Replying there continues it.

## Configuration

Nothing is needed to run bots. Optional properties:

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `agentos.messaging.public-url` | `AGENTOS_PUBLIC_URL` | empty | Base URL of the UI, for the links to a case or to the profile that the bots send. Empty sends no link. |
| `agentos.messaging.link-token-ttl-minutes` | `AGENTOS_MESSAGING_LINK_TOKEN_TTL_MINUTES` | `10` | How long a link to connect an account, or to create a bot, stays valid. |
| `agentos.messaging.telegram.api-base-url` | `AGENTOS_MESSAGING_TELEGRAM_API_BASE_URL` | `https://api.telegram.org` | The Bot API endpoint. |
| `agentos.messaging.telegram.poll-timeout-seconds` | `AGENTOS_MESSAGING_TELEGRAM_POLL_TIMEOUT_SECONDS` | `25` | Long-polling timeout. |

The bots poll Telegram (`getUpdates`): the instance needs no public URL and nothing reaches it from the outside,
which suits an instance behind an access proxy.

## How it works

| Piece | Role |
|---|---|
| `MessagingBot` | A bot AgentOS runs: the bot of an agent in a namespace (role `AGENT`), or the bot manager of a platform (`MANAGER`). Token encrypted at rest. One agent per bot, one bot per agent, platform and namespace, one manager per platform. |
| `MessagingBotService` | Attaches a bot from its token (checked with the platform first), turns it on or off, removes it; sets the bot manager. Publishes `MessagingBotChanged`. |
| `MessagingBotSupervisor` | Runs the bots of the database: all of them when the application is ready, then each one as it is saved or removed, after the change is committed. Keeps their adapter and status in `MessagingBotRegistry`. |
| `MessagingDriver` | One platform: checks a token, starts a bot. `TelegramDriver` runs a `TelegramAdapter` (HTML formatting, splitting, flood waits) fed by a `TelegramUpdatePoller`. |
| `MessagingIdentity` | A platform account linked to a user through a bot. Unique per account and bot, and per user and bot, among active rows. |
| `MessagingConversation` | A chat with a bot and the case its next message goes to. The chat moves between cases; the case does not move. |
| `MessagingGateway` | Takes what the bot of an agent receives: links accounts, checks the user may use the agent, runs the commands, creates and feeds cases as the scheduler does (`create`, ADMIN grant, `publishCaseCreated`, `addMessage`, with `@agent` when the case would otherwise go to another agent). Runs each chat's work with the linked user authenticated. |
| `MessagingManagedBots` | Takes what the bot manager receives: the creation link, the button that opens Telegram's bot creation, and the new bot, which it attaches and links to its creator. |
| `MessagingDelivery` | Listens to `CaseEventCreatedEvent`, published by `CaseEventServiceImpl.create` for every stored event, and sends the readable ones to the chat the case's current turn came from. |
| `MessagingController`, `MessagingBotController` | The UI's endpoints, contracts `MessagingApi` (using bots) and `MessagingBotApi` (configuring them) in `agentos-sdk` (`api.messaging`). |

The origin of a message travels in its session context, under `messaging` (`SessionContextKeys.MESSAGING`):
the platform, which the agent also sees, and the conversation, which tells `MessagingDelivery` where to answer.
The updates of one chat are handled in order, different chats in parallel (`SerialLanes`).

The config agent (`AGENTOS_CONFIG`) reads the bots of a namespace (`messaging`) and tells the user how to give an
agent one: attaching a bot is not a proposal, since it takes the user's confirmation in Telegram or a token they
type.

## Limits of this version

- Direct chats only: messages in groups are ignored.
- Text only: photos, files and voice messages are ignored.
- The pending links (to connect an account or create a bot) and the origin of the current turn of each case are
  kept in memory: after a restart, a pending link has to be asked for again, and a case answers the chat again from
  its next message. A run in progress at the restart is lost anyway, its runtime being in memory too.
- One long poll per running bot, on its own thread: fine for tens of bots.
- Answers are sent once the agent's message is complete, not as it is written.
- A cost pause is shown in the UI only.
