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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * BAAI/bge-small-en-v1.5 run with ONNX Runtime on the CPU, in-process: no service to call, and
 * file contents never leave the server. As the model was trained: a text is represented by its
 * [CLS] token's vector, scaled to unit length, and queries (not passages) are prefixed with the
 * retrieval instruction, which helps short queries like "energy transformation" find passages
 * that describe the idea without naming it.
 */
@Component
public class OnnxEmbedder implements Embedder {

    /** The model was trained on passages up to this long; longer input is cut. */
    static final int MAX_TOKENS = 256;
    static final String QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages: ";
    private static final int BATCH = 16;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;

    @Autowired
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
    public float[][] embedPassages(List<String> passages) {
        return embed(passages);
    }

    @Override
    public float[] embedQuery(String query) {
        return embed(List.of(QUERY_INSTRUCTION + query))[0];
    }

    private float[][] embed(List<String> texts) {
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
            for (int i = 0; i < hidden.length; i++) vectors[i] = normalize(hidden[i][0]); // [CLS]
            return vectors;
        } catch (OrtException e) {
            throw new IllegalStateException("embedding failed", e);
        }
    }

    static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        float scale = (float) (1 / Math.max(Math.sqrt(norm), 1e-12));
        float[] out = new float[v.length];
        for (int d = 0; d < v.length; d++) out[d] = v[d] * scale;
        return out;
    }

    @PreDestroy
    void close() throws OrtException {
        session.close();
    }
}
