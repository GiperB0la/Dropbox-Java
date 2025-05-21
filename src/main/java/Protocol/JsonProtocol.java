package Protocol;

import com.google.gson.Gson;
import java.util.List;

public class JsonProtocol {
    public Operation operation;
    public String fileName;
    public long size;
    public String status;
    public String message;
    public List<String> files;

    public static String toJson(JsonProtocol msg) {
        return new Gson().toJson(msg);
    }

    public static JsonProtocol fromJson(String json) {
        return new Gson().fromJson(json, JsonProtocol.class);
    }
}