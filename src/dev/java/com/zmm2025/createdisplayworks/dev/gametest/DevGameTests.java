package com.zmm2025.createdisplayworks.dev.gametest;

import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import com.zmm2025.createdisplayworks.dev.flipdot.FlipDotTests;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Registers the mod's GameTests, which {@code ./gradlew runGameTestServer} runs on a headless dedicated server.
 *
 * <p>To test a new block, add a class like {@link FlipDotTests} to its dev package, with one
 * {@code @GameTest(templateNamespace = CreateDisplayWorks.MOD_ID, template = GameTestKit.EMPTY)} method per behavior and
 * {@code @PrefixGameTestTemplate(false)} on the class. Build the setup in the test with {@link GameTestKit}'s helpers for
 * power, players and saving, wait with {@code helper.startSequence().thenWaitUntil(...)} rather than fixed delays, and
 * add the class to {@link #CLASSES}.
 */
@EventBusSubscriber(modid = CreateDisplayWorks.MOD_ID)
public final class DevGameTests {
    private static final List<Class<?>> CLASSES = List.of(FlipDotTests.class);

    private DevGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        CLASSES.forEach(event::register);
    }
}
