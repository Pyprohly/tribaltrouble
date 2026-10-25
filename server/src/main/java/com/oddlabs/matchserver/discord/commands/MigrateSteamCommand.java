package com.oddlabs.matchserver.discord.commands;

import com.oddlabs.matchmaking.Profile;
import com.oddlabs.matchserver.Client;
import com.oddlabs.matchserver.DBInterface;
import com.oddlabs.matchserver.DBInterface.SteamLinkResult;
import com.oddlabs.matchserver.MatchmakingServer;
import com.oddlabs.matchserver.SteamAuthValidator;

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;

import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MigrateSteamCommand extends DiscordCommand {

    private static final Pattern STEAM_ID_PATTERN = Pattern.compile("\\d{1,17}");

    private static final long MAX_STEAM_ACCOUNT_ID = 0xFFFFFFFFL;

    private static final String RELOGIN_MESSAGE = "Your Steam account was linked to your old account by a moderator. Log in again to see your profiles.";

    private String command_name = "migrate-steam";
    private String command_description = "Merges a Steam account into an old account found by email. Mod role required.";
    private String command_option_steam_id = "steam_id";
    private String command_option_email = "email";

    @Override
    public String getCommandName() {
        return command_name;
    }

    @Override
    public Mono<Void> executeCommand(ChatInputInteractionEvent event) {
        String steam_id = getStringOption(event, command_option_steam_id);
        String email = getStringOption(event, command_option_email);
        if (steam_id == null || email == null)
            return event.reply("Unable to retrieve the Steam ID and email.").withEphemeral(true);
        OptionalLong account_id = parseSteamAccountId(steam_id.trim());
        if (account_id.isEmpty())
            return event.reply("'" + steam_id + "' is not a SteamID64 or Steam account ID.").withEphemeral(true);
        String moderator = event.getInteraction().getUser().getUsername();

        SteamLinkResult result = DBInterface.linkSteamAccount(account_id.getAsLong(), email.trim());
        return switch (result.status()) {
            case LINKED -> replyLinked(event, account_id.getAsLong(), result.username(), moderator);
            case NO_ACCOUNT -> event.reply("No account uses that email.").withEphemeral(true);
            case SEVERAL_ACCOUNTS -> event.reply(
                    "Several accounts use that email. Link this one by hand in the database.").withEphemeral(true);
            case ALREADY_LINKED -> event.reply(
                    "That Steam account is already linked to the account with that email.").withEphemeral(true);
            case ACCOUNT_HAS_OTHER_STEAM -> event.reply(
                    "The account with that email is already linked to a different Steam account.").withEphemeral(true);
            case STEAM_ON_OTHER_ACCOUNT -> event.reply(
                    "That Steam account is already linked to a different old account.").withEphemeral(true);
            case BLOCKED -> event.reply("One of the two accounts is banned or disabled.").withEphemeral(true);
        };
    }

    private static Mono<Void> replyLinked(ChatInputInteractionEvent event, long account_id, String username,
            String moderator) {
        MatchmakingServer.getLogger().info(
                "Steam account " + account_id + " linked to registration " + username + " by Discord moderator " + moderator);
        Client client = MatchmakingServer.getOnlineUsers().get(DBInterface.STEAM_USERNAME_PREFIX + account_id);
        if (client != null) {
            client.getClientInterface().receivePrivateMessage("Server", RELOGIN_MESSAGE);
            client.close();
        }
        String nicks = Arrays.stream(DBInterface.getProfiles(username, -1)).map(Profile::getNick).collect(
                Collectors.joining(", "));
        return event.reply(
                "Linked Steam account " + account_id + ". Its profiles are now: " + (nicks.isEmpty() ? "none" : nicks) + (client != null ? ". Disconnected the player so they log in again." : ".")).withEphemeral(
                        true);
    }

    /**
     * registrations.steam_id holds the 32-bit account ID, so a SteamID64 loses its base offset.
     */
    private static OptionalLong parseSteamAccountId(String text) {
        if (!STEAM_ID_PATTERN.matcher(text).matches())
            return OptionalLong.empty();
        long id = Long.parseLong(text);
        if (id >= SteamAuthValidator.STEAM_ID_BASE)
            id -= SteamAuthValidator.STEAM_ID_BASE;
        if (id == 0 || id > MAX_STEAM_ACCOUNT_ID)
            return OptionalLong.empty();
        return OptionalLong.of(id);
    }

    @Override
    public ApplicationCommandRequest getCommand() {
        // spotless:off
        ApplicationCommandOptionData steam_id_option = ApplicationCommandOptionData.builder()
                .name(command_option_steam_id)
                .description("SteamID64, or the account ID from the player's save file name")
                .type(ApplicationCommandOption.Type.STRING.getValue())
                .required(true)
                .build();
        ApplicationCommandOptionData email_option = ApplicationCommandOptionData.builder()
                .name(command_option_email)
                .description("Email address the old account was registered with")
                .type(ApplicationCommandOption.Type.STRING.getValue())
                .required(true)
                .build();
        return ApplicationCommandRequest.builder()
                .name(command_name)
                .description(command_description)
                .defaultMemberPermissions(BAN_MEMBERS_PERMISSION)
                .addOption(steam_id_option)
                .addOption(email_option)
                .build();
        // spotless:on
    }
}
