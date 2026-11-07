package io.github.tajudeenj.streaming;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Local cross-encoder reranker (ONNX). It reads the question and each candidate passage together, so it
 * judges whether the passage actually answers the question rather than merely sharing vocabulary. Runs
 * in-process: candidate passages never leave the server. Concurrency is capped so a traffic spike cannot
 * saturate every CPU core.
 */
public final class CrossEncoderReranker implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(CrossEncoderReranker.class.getName());
    private static final int MAX_TOKENS = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final Set<String> inputNames;
    private final Semaphore permits;

    private CrossEncoderReranker(OrtEnvironment env, OrtSession session, HuggingFaceTokenizer tokenizer) throws OrtException {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.inputNames = session.getInputNames();
        this.permits = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /** Loads (downloading once if needed) the model; returns null when disabled or unavailable. */
    public static CrossEncoderReranker loadOrNull(Config cfg) {
        if (!cfg.rerankerEnabled()) {
            LOG.info("reranker disabled by configuration");
            return null;
        }
        try {
            Path dir = cfg.modelCacheDir().resolve("reranker");
            Path model = ModelFiles.fetch(cfg.rerankerModelUri(), dir, "model.onnx", cfg.rerankerModelSha256());
            Path tok = ModelFiles.fetch(cfg.rerankerTokenizerUri(), dir, "tokenizer.json", "");
            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tok)
                    .optMaxLength(MAX_TOKENS)
                    .optTruncateSecondOnly()
                    .optPadding(false)
                    .build();
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            OrtSession session = env.createSession(model.toString(), opts);
            LOG.info("reranker loaded: inputs=" + session.getInputNames());
            return new CrossEncoderReranker(env, session, tokenizer);
        } catch (Exception e) {
            LOG.warning("reranker unavailable, falling back to lexical relevance gate: " + e.getMessage());
            return null;
        }
    }

    /**
     * @return one relevance logit per passage (higher is more relevant), or null when the reranker is busy
     *         beyond the wait budget, so the caller can degrade instead of queueing indefinitely.
     */
    public float[] score(String query, List<String> passages) throws OrtException, InterruptedException {
        if (passages.isEmpty()) return new float[0];
        if (!permits.tryAcquire(2, TimeUnit.SECONDS)) return null;
        try {
            int n = passages.size();
            Encoding[] enc = new Encoding[n];
            int maxLen = 1;
            for (int i = 0; i < n; i++) {
                enc[i] = tokenizer.encode(query, passages.get(i));
                maxLen = Math.max(maxLen, enc[i].getIds().length);
            }
            long[][] ids = new long[n][maxLen];
            long[][] mask = new long[n][maxLen];
            long[][] types = new long[n][maxLen];
            for (int i = 0; i < n; i++) {
                long[] a = enc[i].getIds(), m = enc[i].getAttentionMask(), t = enc[i].getTypeIds();
                System.arraycopy(a, 0, ids[i], 0, a.length);
                System.arraycopy(m, 0, mask[i], 0, m.length);
                System.arraycopy(t, 0, types[i], 0, t.length);
            }
            Map<String, OnnxTensor> inputs = new HashMap<>();
            try {
                inputs.put("input_ids", OnnxTensor.createTensor(env, ids));
                inputs.put("attention_mask", OnnxTensor.createTensor(env, mask));
                if (inputNames.contains("token_type_ids")) inputs.put("token_type_ids", OnnxTensor.createTensor(env, types));
                try (OrtSession.Result r = session.run(inputs)) {
                    float[][] logits = (float[][]) r.get(0).getValue();
                    float[] out = new float[n];
                    for (int i = 0; i < n; i++) out[i] = logits[i][0];
                    return out;
                }
            } finally {
                for (OnnxTensor t : inputs.values()) t.close();
            }
        } finally {
            permits.release();
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
        tokenizer.close();
    }
}
