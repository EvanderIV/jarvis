package com.jarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import com.jarvis.ParsedCommand.Action;

public class WakeWordMatcherTest {

    private WakeWordMatcher jarvis;

    @Before
    public void setUp() {
        jarvis = new WakeWordMatcher(WakeWordMatcher.triggersFor("jarvis"));
    }

    // Transcriptions from a real prod log of people chatting over a game. The first
    // five used to start or stop the music.
    private static final String[] OVERHEARD = {
        "they are just gives them more the rp get urge to kill another person in the are be gay",
        "why in the world is this even a place where you can be",
        "why did this just exploded",
        "guess slides in underneath me try to get a kill but i just jump over there",
        "we just played mario kart where the guy are run off the road i guess",
        "just like shooting where they're at",
        "this take looks like it should be drivable but it's",
        "this it wants their cheeks",
        "so i guess we're all just lurking now like we have jobs",
        "it just it's just lately embedded in the rock that's the only tell",
        "gosh to thing i'm almost killed him",
        "casting and i'm retarded he switched to a secondary and got the kill",
        "i'm really having a hard time staying alive in this game",
        "oh wow we just rolled in this game",
        "the fighting in the elevator shaft",
        "what the whole who's shooting at me",
        "that's the downside of switching guns as you can never get the right",
        "i'd fly so high up",
        "or whoever that helicopter pilot is bubbly rob roy or whatever they'll and on how to pronounce it",
        "oh and then to take sneaks up behind me and then just blows my bad but out you",
        "that",
        "",
    };

    @Test
    public void overheardChatterIsIgnored() throws Exception {
        IntentParser parser = new IntentParser();
        // Prime the context the way the real session did: music had been played earlier
        parser.parse("play some music");

        for (String transcript : OVERHEARD) {
            String command = jarvis.extractCommand(transcript);
            if (command == null) continue;
            try {
                ParsedCommand parsed = parser.parse(command);
                if (parsed.isValid()) {
                    fail("Overheard chatter triggered " + parsed + ": \"" + transcript + "\"");
                }
            } catch (MissingContextualTargetException expected) {
                // fine — nothing would have happened
            }
        }
    }

    @Test
    public void midSentenceTriggerWordsDontWake() {
        assertNull(jarvis.extractCommand("we just played mario kart"));
        assertNull(jarvis.extractCommand("i was talking to george about it"));
        assertNull(jarvis.extractCommand("i told george to kill it"));
        assertNull(jarvis.extractCommand("jarvis"));
    }

    @Test
    public void sentenceStartersBeforeTheName() {
        // How Vosk transcribed "<starter> Jarvis, kill the music" from TTS samples
        String[] heard = {
            "hey jarvis", "yo jarvis", "your jarvis", "yeah jarvis", "yo yo jarvis", "a o jarvis", "hi jarvis",
            "hello jarvis", "okay jarvis", "okay so jarvis", "alright jarvis", "right jarvis", "excuse me jarvis",
            "hey there jarvis", "oh jarvis", "ah jarvis", "i'll jarvis", "our jarvis", "i'm jarvis", "him jarvis",
            "a jarvis", "i jarvis", "oi jarvis", "or jarvis", "sub jarvis", "so jarvis", "well jarvis",
            "please jarvis", "listen jarvis",
        };
        for (String prefix : heard) {
            assertEquals(prefix, "kill the music", jarvis.extractCommand(prefix + " kill the music"));
        }
    }

    @Test
    public void realCommandsStillWork() throws Exception {
        IntentParser parser = new IntentParser();
        assertEquals("kill the music", jarvis.extractCommand("jarvis kill the music"));
        assertEquals("kill the music", jarvis.extractCommand("hey jarvis kill the music"));
        assertEquals("kill the music", jarvis.extractCommand("a jarvis kill the music"));
        assertEquals("play some jazz music", jarvis.extractCommand("okay travis play some jazz music"));

        assertEquals(Action.TURN_OFF, parser.parse(jarvis.extractCommand("jarvis kill the music")).action);
        assertEquals(Action.PLAY_MUSIC, parser.parse(jarvis.extractCommand("jarvis play some music")).action);
    }

    @Test
    public void alternatePhrasesWorkAnywhere() {
        assertEquals("good morning", jarvis.extractCommand("well good morning jarvis how are you").substring(5, 17));
        assertEquals("coachella music", jarvis.extractCommand("coachella music"));
    }

    @Test
    public void ineffaWakeWordAnchored() {
        WakeWordMatcher ineffa = new WakeWordMatcher(WakeWordMatcher.triggersFor("ineffa"));
        assertEquals("kill the music", ineffa.extractCommand("he never kill the music"));
        assertNull(ineffa.extractCommand("so then i realized i never kill the music"));
    }
}
