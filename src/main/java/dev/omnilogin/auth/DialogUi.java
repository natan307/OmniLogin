package dev.omnilogin.auth;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.entity.Player;

/**
 * Login and register forms as Minecraft dialogs (Paper 1.21.7+, clients 1.21.6+). Loaded by reflection only when the
 * Dialog API exists, so older servers never touch this class.
 */
public final class DialogUi implements AuthUi {

    private static final int FIELD_WIDTH = 250;
    private static final int BUTTON_WIDTH = 150;
    private static final int MAX_LENGTH = 64;

    private final AuthManager manager;

    public DialogUi(AuthManager manager) {
        this.manager = manager;
    }

    @Override
    public void show(Player player, boolean register, String error) {
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("password", text("dialog-label-password"))
                .width(FIELD_WIDTH).maxLength(MAX_LENGTH).build());
        if (register) {
            inputs.add(DialogInput.text("confirm", text("dialog-label-confirm"))
                    .width(FIELD_WIDTH).maxLength(MAX_LENGTH).build());
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text(register ? "dialog-hint-register" : "dialog-hint-login")));
        if (error != null) {
            body.add(DialogBody.plainMessage(legacy(error)));
        }

        ActionButton submit = ActionButton.create(
                text(register ? "dialog-button-register" : "dialog-button-login"),
                text(register ? "dialog-hint-register" : "dialog-hint-login"),
                BUTTON_WIDTH,
                DialogAction.customClick((view, audience) -> {
                    if (!(audience instanceof Player clicker)) {
                        return;
                    }

                    String password = view.getText("password");
                    password = password == null ? "" : password;
                    if (register) {
                        String confirm = view.getText("confirm");
                        manager.register(clicker, password, confirm == null ? "" : confirm, true);
                    } else {
                        manager.login(clicker, password, true);
                    }
                }, ClickCallback.Options.builder().build()));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text(register ? "dialog-title-register" : "dialog-title-login"))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.notice(submit)));

        player.showDialog(dialog);
    }

    private Component text(String key) {
        return legacy(manager.message(key));
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }
}
