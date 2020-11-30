package io.horrorshow.codey.challenge;

import io.horrorshow.codey.api.WandboxApi;
import io.horrorshow.codey.challenge.xml.Problem;
import io.horrorshow.codey.challenge.xml.TestCase;
import io.horrorshow.codey.discordutil.DiscordMessageParser;
import io.horrorshow.codey.discordutil.DiscordUtils;
import io.horrorshow.codey.discordutil.MessagePart;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class CodingCompetition extends ListenerAdapter {

    private static final String CREATE_CHALLENGE = "$create";
    private static final String SHOW_CHALLENGE = "$show";

    private static final String VERIFY = "\uD83C\uDF00";

    private final WandboxApi wandboxApi;

    private final List<Problem> problemList = new ArrayList<>();

    private final Map<TextChannel, Challenge> challenges = new HashMap<>();

    private final Random random = new Random();
    private final DiscordUtils utils;

    public CodingCompetition(@Autowired JDA jda,
                             @Autowired WandboxApi wandboxApi,
                             @Autowired @Value("${challenge.path}") String challengePath,
                             @Autowired DiscordUtils utils) throws IOException, JAXBException {
        this.wandboxApi = wandboxApi;
        this.utils = utils;

        jda.addEventListener(this);

        loadProblemList(challengePath);
    }

    private void loadProblemList(String challengePath) throws JAXBException, IOException {
        var context = JAXBContext.newInstance(Problem.class);
        var um = context.createUnmarshaller();

        var path = Paths.get(challengePath);
        log.info("looking for challenges in {}", path.toAbsolutePath().toString());
        try (Stream<Path> stream = Files.walk(path, 1)) {
            var files = stream.filter(file -> !Files.isDirectory(file))
                    .map(Path::toFile)
                    .collect(Collectors.toList());
            log.info("found {} files in {}", files.size(), path.toString());
            for (File file : files) {
                problemList.add((Problem) um.unmarshal(file));
            }
        }
    }

    @Override
    public void onGuildMessageReceived(@NotNull GuildMessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        final var raw = event.getMessage().getContentRaw();
        final var channel = event.getChannel();
        if (CREATE_CHALLENGE.equals(raw)) {
            onCreateChallenge(channel);
        } else if (SHOW_CHALLENGE.equals(raw)) {
            onShowChallenge(channel);
        }

        getActiveChallenge(channel).flatMap(challenge -> DiscordMessageParser.of(raw)
                .getParts().stream()
                .filter(MessagePart::isCode)
                .findAny())
                .ifPresent(p -> event.getMessage().addReaction(VERIFY).queue());
    }

    private Optional<Challenge> getActiveChallenge(TextChannel channel) {
        return Optional.ofNullable(challenges.get(channel))
                .filter(challenge -> challenge.getState() == State.ACTIVE);
    }

    @Override
    public void onGuildMessageReactionAdd(@NotNull GuildMessageReactionAddEvent event) {
        if (event.getUser().isBot()) return;

        var channel = event.getChannel();
        if (VERIFY.equals(event.getReactionEmote().getEmoji())) {
            getActiveChallenge(channel).ifPresentOrElse(challenge ->
                            challengePresent(event, channel, challenge)
                    , () -> utils.sendRemovableMessage("No active challenge", channel));
        }
    }

    private void challengePresent(@NotNull GuildMessageReactionAddEvent event,
                                  TextChannel channel,
                                  Challenge challenge) {
        channel.retrieveMessageById(event.getMessageId())
                .queue(message -> DiscordMessageParser.of(message.getContentRaw())
                        .getParts().stream()
                        .filter(MessagePart::isCode)
                        .forEach(part ->
                                runTests(part.getText(), part.getLang(), challenge,
                                        result -> utils.sendRemovableMessage(result, channel))));
    }

    @Async
    public void runTests(String text, String lang, Challenge challenge, Consumer<String> resultCb) {
        utils.sendRemovableMessage("verifying for challenge "
                + challenge.getProblem().getName()
                + " code: ```" + lang + text + "```", challenge.getChannel());

        // TODO create incoming Test results handling class that represents a result
        // it waits for incoming x amount of time for all wandbox responses
        // and has info about testcases, what failed, name, problem, etc
        // which should also prevent false positives
        var noTestsPass = new AtomicInteger(0);
        var noResults = new AtomicInteger(0);
        final List<TestCase> caseList = challenge.getProblem().getTestcases().getTestcase();
        for (var test : caseList) {
            wandboxApi.compile(text, lang, test.getInput(), "",
                    response -> {
                        log.info("Actual: '{}' Expected: '{}'",
                                response.getProgram_output().trim(), test.getOutput());
                        var i = noResults.incrementAndGet();
                        if (test.getOutput().equals(response.getProgram_output().trim())) {
                            var testsPass = noTestsPass.incrementAndGet();
                            if (testsPass == caseList.size()) {
                                resultCb.accept("Congratz! All " + noTestsPass + " tests pass");
                            }
                        } else if (i == caseList.size()) {
                            resultCb.accept("you loose, only " + noTestsPass.get() + "/" + i + " test cases correct");
                        }
                    });
        }
    }

    private void onCreateChallenge(TextChannel channel) {
        if (problemList.isEmpty()) {
            utils.sendRemovableMessage("No challenges found", channel);
        } else {
            var randProblem = problemList.get(random.nextInt(problemList.size()));
            var challenge = new Challenge(randProblem, channel, this);
            challenges.put(channel, challenge);
            utils.sendRemovableMessage("*New Challenge! Good luck*\n\n%s"
                    .formatted(challenge), channel);
        }
    }

    private void onShowChallenge(TextChannel channel) {
        if (challenges.containsKey(channel)) {
            var challenge = challenges.get(channel);
            switch (challenge.getState()) {
                case ACTIVE -> utils.sendRemovableMessage("Challenge ACTIVE:%n%s"
                        .formatted(challenge), channel);
                case DONE -> utils.sendRemovableMessage("Challenge DONE", channel);
            }
        } else {
            utils.sendRemovableMessage("No challenge in this channel available", channel);
        }
    }

    public void onChallengeTimeUp(Challenge challenge) {
        utils.sendRemovableMessage("Time is up for challenge:%n%s"
                .formatted(challenge), challenge.getChannel());
    }
}