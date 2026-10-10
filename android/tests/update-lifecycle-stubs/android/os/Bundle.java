package android.os;
import java.util.*;
public class Bundle {
    private final Map<String,String[]> values=new HashMap<>();
    public void putStringArray(String k,String[] v) {
        values.put(k,v.clone());
    }
    public String[] getStringArray(String k) {
        String[] v=values.get(k);
        return v==null?null:v.clone();
    }
}
