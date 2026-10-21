package core;

import com.github.kotlintelegrambot.Bot;
import com.github.kotlintelegrambot.entities.ChatId;
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup;
import core.ui.ScreenRenderer;
import kotlin.Unit;
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton;
import com.sun.net.httpserver.HttpServer;
import core.keyboards.KeyboardMemes;
import core.routers.RouterMemes;
import data.repositories.MemeRepository;
import data.repositories.UserRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Standalone smoke test: runs against a local Telegram stub, without credentials or a database. */
public final class MemeNavigationSmokeTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static List<InlineKeyboardButton.CallbackData> buttons(InlineKeyboardMarkup markup) {
        List<InlineKeyboardButton.CallbackData> result = new ArrayList<>();
        for (List<InlineKeyboardButton> row : markup.getInlineKeyboard()) {
            for (InlineKeyboardButton button : row) result.add((InlineKeyboardButton.CallbackData) button);
        }
        return result;
    }
    private static boolean callback(InlineKeyboardMarkup markup, String data) {
        return buttons(markup).stream().anyMatch(button -> button.getCallbackData().equals(data));
    }
    private static void checkRoutes(InlineKeyboardMarkup keyboard) {
        keyboard.getInlineKeyboard().forEach(row -> row.forEach(button ->
            check(RouterMemes.INSTANCE.isNavigation(button.getText()), "Unrouted button: " + button.getText())));
    }
    public static void main(String[] args) throws Exception {
        KeyboardMemes keyboards = KeyboardMemes.INSTANCE;
        checkRoutes(keyboards.memesMenu());
        checkRoutes(keyboards.memesViewerMenu());
        checkRoutes(keyboards.memesFavoritesMenu());
        checkRoutes(keyboards.memesAddMenu());
        InlineKeyboardMarkup feed = keyboards.memeActionsInline(42L, 3, 1, false, 1, null, 0);
        check(callback(feed, "meme_next"), "Feed needs next button");
        check(buttons(feed).stream().anyMatch(b -> b.getText().equals("👍 3 ✓")), "Selected vote and count");
        check(buttons(feed).stream().anyMatch(b -> b.getText().equals("☆ В избранное")), "Save label");
        InlineKeyboardMarkup first = keyboards.memeActionsInline(42L, 3, 1, true, 0, 0, 3);
        check(!callback(first, "meme_page_42_-1"), "No previous on first favorite");
        check(callback(first, "meme_page_42_1"), "Next on first favorite");
        check(callback(first, "meme_fav_42_saved"), "Removal retains gallery context");
        check(callback(first, "meme_vote_42_1_saved"), "Voting retains gallery context");
        check(!callback(first, "meme_next"), "Gallery should not jump into random feed");
        InlineKeyboardMarkup middle = keyboards.memeActionsInline(42L, 0, 0, true, -1, 1, 3);
        check(callback(middle, "meme_page_42_-1") && callback(middle, "meme_page_42_1"), "Middle favorite has both directions");
        InlineKeyboardMarkup last = keyboards.memeActionsInline(42L, 0, 0, true, 0, 2, 3);
        check(!callback(last, "meme_page_42_1"), "No next on last favorite");
        InlineKeyboardMarkup single = keyboards.memeActionsInline(42L, 0, 0, true, 0, 0, 1);
        check(buttons(single).stream().noneMatch(b -> b.getCallbackData().startsWith("meme_page_")), "Single favorite has no arrows");
        for (InlineKeyboardButton.CallbackData button : buttons(middle)) {
            check(button.getCallbackData().getBytes(StandardCharsets.UTF_8).length <= 64, "Telegram callback limit");
        }

        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            synchronized (requests) { requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); }
            byte[] response = "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1,\"chat\":{\"id\":123,\"type\":\"private\"},\"text\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Bot.Builder builder = new Bot.Builder();
            builder.setToken("test-only");
            builder.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Bot bot = builder.build();
            Session session = SessionStore.INSTANCE.get(123L);
            session.getData().put("profile_impersonated_user_id", "456");
            MemeRepository memes = new MemeRepository();
            UserRepository users = new UserRepository();
            ChatId chat = ChatId.Companion.fromId(123L);
            RouterMemes router = RouterMemes.INSTANCE;
            ScreenRenderer.INSTANCE.transaction(bot, 123L, 123L, null, ignored -> {
            check(router.handleMemeAction(bot, chat, 456L, "😂 Мемы", memes, users, session), "Open menu");
            check("menu".equals(session.getData().get("meme_screen")), "Menu uses sender session during impersonation");
            check(session.getAction() == PendingAction.NONE, "Menu clears pending action");
            check(router.handleMemeAction(bot, chat, 456L, "➕ Добавить мем", memes, users, session), "Begin upload");
            check(session.getAction() == PendingAction.ADD_MEME, "Upload waits for photo");
            check(router.handleMemeAction(bot, chat, 456L, "Just text", memes, users, session), "Text during upload gets guidance");
            check(session.getAction() == PendingAction.ADD_MEME, "Invalid text retains upload state");
            router.handleMemeAction(bot, chat, 456L, "❌ Отменить добавление", memes, users, session);
            check(session.getAction() == PendingAction.NONE && "menu".equals(session.getData().get("meme_screen")), "Cancel returns to menu");
            check("456".equals(session.getData().get("profile_impersonated_user_id")), "Cancel preserves impersonation");
            router.handleMemeAction(bot, chat, 456L, "Добавить мем", memes, users, session);
            check(session.getAction() == PendingAction.ADD_MEME, "Legacy upload button works");
            router.handleMemeAction(bot, chat, 456L, "⬅️ Обратно", memes, users, session);
            check(session.getAction() == PendingAction.NONE, "Legacy back cancels upload");
            check(!router.handleMemeAction(bot, chat, 456L, "Unrelated text", memes, users, session), "Unrelated text is not swallowed");
            router.handleMemeAction(bot, chat, 456L, "🏠 Главное меню", memes, users, session);
            check(!session.getData().containsKey("meme_screen"), "Home exits meme context");
            return Unit.INSTANCE;
            });
            check(requests.size() == 2, "Expected migration notice and one assembled screen, got " + requests.size());
            System.out.println("PASS: " + checks + " checks (menus, gallery boundaries, callbacks, upload, cancellation, impersonation).");
        } finally {
            server.stop(0);
            SessionStore.INSTANCE.clear(123L);
        }
    }
}
