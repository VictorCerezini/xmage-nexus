package mage.client.bridge.processor;

import java.util.List;
import java.util.UUID;

/**
 * The mana source the bridge or the model tapped last while paying, kept so that the prompts tapping it brings
 * (the source's own cost, such as a Signet's {1}, or which of its mana abilities to use) are answered with the
 * whole payment in mind.
 *
 * @param sourceId  the source that was tapped
 * @param paying    the cost of the prompt it was tapped for
 * @param stillOwed what the payment that prompt is part of needs after it: nothing, unless the prompt was itself
 *                  a source's own cost
 * @param abilities the source's mana abilities, as the server words them
 * @param after     the plain sources a mana plan taps after it, and auto-tap after the plan (empty without a plan)
 */
record BridgeManaTap(
        UUID sourceId,
        BridgeManaSources.Cost paying,
        BridgeManaSources.Cost stillOwed,
        List<String> abilities,
        List<BridgeManaSources.Source> after) {
}
