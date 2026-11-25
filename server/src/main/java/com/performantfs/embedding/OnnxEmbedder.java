package com.performantfs.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.performantfs.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * all-MiniLM-L6-v2 run with ONNX Runtime on the CPU, in-process: no service to call, and file
 * contents never leave the server. Same output as sentence-transformers: the token vectors are
 * averaged (padding excluded) and scaled to unit length.
 */
@Component
public class OnnxEmbedder implements Embedder {

    /** The model was trained on passages up to this long; longer input is cut. */
    static final int MAX_TOKENS = 256;
    private static final int BATCH = 16;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;

    public OnnxEmbedder(AppProperties props) throws IOException, OrtException {
        this(props.embeddingModelDir());
    }

    public OnnxEmbedder(Path modelDir) throws IOException, OrtException {
        Path model = modelDir.resolve("model.onnx");
        Path vocab = modelDir.resolve("vocab.txt");
        if (!Files.isRegularFile(model) || !Files.isRegularFile(vocab)) {
            throw new IllegalStateException("Embedding model not found in " + modelDir.toAbsolutePath()
                    + ". Run ./gradlew downloadModel, or set pfs.embedding-model-dir.");
        }
        this.tokenizer = WordPieceTokenizer.load(vocab);
        var options = new OrtSession.SessionOptions();
        // Leave cores for requests: indexing is background work.
        options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(model.toString(), options);
    }

    @Override
    public float[][] embed(List<String> texts) {
        float[][] out = new float[texts.size()][];
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
            float[][] vectors = embedBatch(batch);
            System.arraycopy(vectors, 0, out, from, vectors.length);
        }
        return out;
    }

    private float[][] embedBatch(List<String> texts) {
        List<long[]> encoded = new ArrayList<>(texts.size());
        int width = 0;
        for (String text : texts) {
            long[] ids = tokenizer.encode(text, MAX_TOKENS);
            encoded.add(ids);
            width = Math.max(width, ids.length);
        }
        long[][] ids = new long[texts.size()][width];
        long[][] mask = new long[texts.size()][width];
        long[][] types = new long[texts.size()][width];
        for (int i = 0; i < encoded.size(); i++) {
            long[] row = encoded.get(i);
            System.arraycopy(row, 0, ids[i], 0, row.length);
            for (int j = 0; j < row.length; j++) mask[i][j] = 1;
        }
        try (OnnxTensor idsT = OnnxTensor.createTensor(env, ids);
             OnnxTensor maskT = OnnxTensor.createTensor(env, mask);
             OnnxTensor typesT = OnnxTensor.createTensor(env, types);
             OrtSession.Result result = session.run(Map.of("input_ids", idsT, "attention_mask", maskT, "token_type_ids", typesT))) {
            float[][][] hidden = (float[][][]) result.get(0).getValue();
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < hidden.length; i++) vectors[i] = meanPoolAndNormalize(hidden[i], mask[i]);
            return vectors;
        } catch (OrtException e) {
            throw new IllegalStateException("embedding failed", e);
        }
    }

    static float[] meanPoolAndNormalize(float[][] tokens, long[] mask) {
        float[] sum = new float[tokens[0].length];
        int count = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) continue;
            count++;
            for (int d = 0; d < sum.length; d++) sum[d] += tokens[t][d];
        }
        double norm = 0;
        for (int d = 0; d < sum.length; d++) {
            sum[d] /= Math.max(count, 1);
            norm += (double) sum[d] * sum[d];
        }
        float scale = (float) (1 / Math.max(Math.sqrt(norm), 1e-12));
        for (int d = 0; d < sum.length; d++) sum[d] *= scale;
        return sum;
    }

    @PreDestroy
    void close() throws OrtException {
        session.close();
    }
}
