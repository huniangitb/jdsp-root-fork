import java.lang.reflect.Method;

/** EffectProbe — lists the AudioEffect methods that the offline render needs. */
public final class EffectProbe {
    public static void main(String[] args) {
        for (Method m : android.media.audiofx.AudioEffect.class.getMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("process") || name.contains("parameter")) {
                StringBuilder sb = new StringBuilder();
                sb.append("METHOD ").append(m.getName()).append("(");
                Class<?>[] types = m.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(types[i].getSimpleName());
                }
                sb.append(") ->").append(m.getReturnType().getSimpleName());
                System.out.println(sb);
            }
        }
    }
}
