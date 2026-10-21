package core;

import core.keyboards.KeyboardProfile;
import data.models.AccountType;
import data.models.UserProfile;
import data.repositories.UserRepository;
import data.services.AdminService;

/** Read-only role regression checks; uses the Compose environment and existing database. */
public final class AdminRoleSmokeTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        AdminService admins = AdminService.INSTANCE;
        check(!admins.getAdminIds().isEmpty(), "Expected configured administrators");
        long ordinaryId = Long.MIN_VALUE;
        check(!admins.isBootstrapAdmin(ordinaryId), "Test ID must not be configured");
        check(admins.resolveAccountType(ordinaryId, AccountType.USER) == AccountType.USER, "Ordinary user stays user");
        check(admins.resolveAccountType(ordinaryId, AccountType.MODERATOR) == AccountType.MODERATOR, "Moderator role preserved");
        check(admins.resolveAccountType(ordinaryId, AccountType.ADMIN) == AccountType.ADMIN, "Database admin role preserved");
        check(!admins.isAdmin(ordinaryId), "Unregistered user has no admin rights");
        for (long ownerId : admins.getAdminIds()) {
            check(admins.resolveAccountType(ownerId, AccountType.USER) == AccountType.ADMIN, "Configured admin overrides stored user");
            check(admins.resolveAccountType(ownerId, AccountType.MODERATOR) == AccountType.ADMIN, "Configured admin overrides stored moderator");
            check(admins.getAccountType(ownerId) == AccountType.ADMIN, "Authorization resolves admin role");
            check(admins.isAdmin(ownerId), "Admin actions are authorized");
            check(admins.isModerator(ownerId), "Admin has moderation rights");
            UserProfile profile = new UserRepository().profile(ownerId);
            check(profile != null, "Configured owner profile exists");
            check(profile.getAccountType() == AccountType.ADMIN, "Profile displays effective admin role");
            boolean hasAdminPanel = KeyboardProfile.INSTANCE.profileMenu(profile, null).getInlineKeyboard().stream()
                .flatMap(row -> row.stream()).anyMatch(button -> button.getText().equals("🛡️ Админ панель"));
            check(hasAdminPanel, "Profile keyboard includes admin panel");
        }
        System.out.println("PASS: " + checks + " read-only admin role checks.");
    }
}
