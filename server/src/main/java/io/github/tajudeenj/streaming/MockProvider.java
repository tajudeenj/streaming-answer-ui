package io.github.tajudeenj.streaming;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Offline provider for demos and load tests: streams a canned answer at a realistic, jittered pace. */
public final class MockProvider implements LlmProvider {

    private static final String MURABAHA = """
            In a Murabaha, the bank buys the asset you need and sells it to you at a disclosed cost plus an agreed profit. \
            The price is fixed when the contract is signed, so your total payable does not rise if market rates move later.

            That is the core difference from interest. Interest is a charge on money lent over time. Murabaha profit is \
            part of the sale price of a real asset that the bank owned, and carried the risk of, before selling it to you.

            Two practical points follow. The bank cannot add compounding charges for late payment; any late-payment amount \
            is usually directed to charity. And early settlement may earn a rebate on the unearned profit, at the bank's discretion.""";

    private static final String IJARAH = """
            In an Ijarah, the bank owns the asset and leases it to you. Because ownership stays with the bank, major \
            damage that is not caused by your misuse is the bank's responsibility, typically covered through Takaful.

            You remain responsible for ordinary operating upkeep and for damage caused by negligence. If the asset is \
            destroyed and can no longer be used, rent normally stops from that point, since you cannot pay rent for \
            something you cannot use.""";

    private static final String GENERAL = """
            Islamic finance products are built on trade, leasing or partnership rather than lending money for interest. \
            Each product links the bank's return to a real asset or venture, and the bank shares some of the risk that \
            ownership brings.

            The common structures are Murabaha for purchases, Ijarah for leasing, Musharakah and Mudarabah for \
            partnerships, and Takaful for cooperative protection. Ask about any of these and I will explain how it \
            works and what it means for you as a customer.""";

    @Override
    public String model() { return "mock"; }

    @Override
    public void stream(List<ChatMessage> messages, TokenSink sink) throws java.io.IOException, InterruptedException {
        String q = messages.get(messages.size() - 1).content().toLowerCase(Locale.ROOT);
        String text = q.contains("ijar") || q.contains("lease") || q.contains("damage") ? IJARAH
                : q.contains("murabah") || q.contains("interest") || q.contains("pricing") ? MURABAHA
                : GENERAL;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Thread.sleep(250 + rnd.nextInt(250)); // simulated time-to-first-token
        int k = 0;
        while (k < text.length()) {
            int end = text.indexOf(' ', k + 1);
            end = end < 0 ? text.length() : end;
            sink.accept(text.substring(k, end));
            k = end;
            Thread.sleep(18 + rnd.nextInt(30));
        }
    }
}
