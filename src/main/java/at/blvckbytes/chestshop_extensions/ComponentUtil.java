package at.blvckbytes.chestshop_extensions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import javax.annotation.Nullable;
import java.util.function.Consumer;

public class ComponentUtil {

  public static String[] getSignLines(Sign sign) {
    return getSignLines(sign.getSide(Side.FRONT));
  }

  public static String[] getSignLines(SignSide signSide) {
    var lines = signSide.lines();
    var result = new String[lines.size()];

    for (var i = 0; i < lines.size(); ++i)
      result[i] = componentToText(lines.get(i));

    return result;
  }

  public static String componentToText(@Nullable Component component) {
    if (component == null)
      return "";

    var result = new StringBuilder();

    forEachChildrenAndSelf(component, current -> {
      if (current instanceof TextComponent textComponent)
        result.append(textComponent.content());
    });

    return result.toString();
  }

  public static void forEachChildrenAndSelf(Component component, Consumer<Component> handler) {
    handler.accept(component);

    for (var child : component.children())
      forEachChildrenAndSelf(child, handler);
  }
}
