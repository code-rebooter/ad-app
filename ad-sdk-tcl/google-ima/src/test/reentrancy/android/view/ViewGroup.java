package android.view;

import java.util.ArrayList;
import java.util.List;

public class ViewGroup {
    public final List<Runnable> posted = new ArrayList<>();
    public void setAlpha(float alpha) { }
    public int getWidth() { return 100; }
    public int getHeight() { return 100; }
    public void removeAllViews() { }
    public boolean post(Runnable task) { posted.add(task); return true; }
}
