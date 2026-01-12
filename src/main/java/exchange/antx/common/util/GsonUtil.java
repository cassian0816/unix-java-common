package exchange.antx.common.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.LongSerializationPolicy;

import java.math.BigDecimal;

public class GsonUtil {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setLongSerializationPolicy(LongSerializationPolicy.STRING)
            .registerTypeAdapter(
                    BigDecimal.class,
                    (JsonSerializer<BigDecimal>) (src, typeOfSrc, context) -> new JsonPrimitive(src.toPlainString()))
            .create();

    public static Gson gson() {
        return GSON;
    }

}
