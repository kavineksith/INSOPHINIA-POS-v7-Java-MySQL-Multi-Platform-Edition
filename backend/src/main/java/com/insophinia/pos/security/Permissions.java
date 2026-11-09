package com.insophinia.pos.security;

import com.insophinia.pos.entity.UserRole;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** RBAC permission matrix. */
public final class Permissions {

    private Permissions() {}

    public static final String VIEW_DASHBOARD = "view:dashboard";
    public static final String VIEW_ITEMS = "view:items";
    public static final String MANAGE_ITEMS = "manage:items";
    public static final String VIEW_CATEGORIES = "view:categories";
    public static final String MANAGE_CATEGORIES = "manage:categories";
    public static final String VIEW_INVENTORY = "view:inventory";
    public static final String MANAGE_INVENTORY = "manage:inventory";
    public static final String VIEW_PROMOTIONS = "view:promotions";
    public static final String MANAGE_PROMOTIONS = "manage:promotions";
    public static final String VIEW_CUSTOMERS = "view:customers";
    public static final String MANAGE_CUSTOMERS = "manage:customers";
    public static final String VIEW_BILLING = "view:billing";
    public static final String MANAGE_BILLING = "manage:billing";
    public static final String VIEW_REPORTS = "view:reports";
    public static final String EXPORT_REPORTS = "export:reports";
    public static final String VIEW_USERS = "view:users";
    public static final String MANAGE_USERS = "manage:users";
    public static final String VIEW_SETTINGS = "view:settings";
    public static final String MANAGE_SETTINGS = "manage:settings";
    public static final String VIEW_SECURITY = "view:security";
    public static final String MANAGE_SECURITY = "manage:security";
    public static final String VIEW_LOGS = "view:logs";
    public static final String AUTHORIZE_RETURNS = "authorize:returns";
    public static final String AUTHORIZE_LOYALTY = "authorize:loyalty";
    public static final String MANAGE_SYSTEM_LOCK = "manage:system_lock";

    // v7 feature permissions --------------------------------------------------
    // Warranties: any authenticated role can view; supervisor+ can file/resolve claims.
    public static final String VIEW_WARRANTIES = "view:warranties";
    public static final String MANAGE_WARRANTIES = "manage:warranties"; // file claims, resolve, void
    // Repairs: supervisor+ create / progress repair jobs.
    public static final String VIEW_REPAIRS = "view:repairs";
    public static final String MANAGE_REPAIRS = "manage:repairs";
    // Email templates: admin+ edit (templates can be used in marketing — locked down).
    public static final String VIEW_EMAIL_TEMPLATES = "view:email_templates";
    public static final String MANAGE_EMAIL_TEMPLATES = "manage:email_templates";
    // Marketing: admin+ creates/sends campaigns. master_admin only sees the failed_logs audit.
    public static final String VIEW_MARKETING = "view:marketing";
    public static final String MANAGE_MARKETING = "manage:marketing";
    // Bill multi-channel send: cashier+ can resend a bill via WhatsApp / email.
    // These don't need a separate permission — they're covered by manage:billing.

    private static final Map<UserRole, List<String>> MATRIX = Map.of(
            UserRole.master_admin, List.of(
                    VIEW_DASHBOARD, VIEW_ITEMS, MANAGE_ITEMS, VIEW_CATEGORIES, MANAGE_CATEGORIES,
                    VIEW_INVENTORY, MANAGE_INVENTORY, VIEW_PROMOTIONS, MANAGE_PROMOTIONS,
                    VIEW_CUSTOMERS, MANAGE_CUSTOMERS, VIEW_BILLING, MANAGE_BILLING,
                    VIEW_REPORTS, EXPORT_REPORTS, VIEW_USERS, MANAGE_USERS,
                    VIEW_SETTINGS, MANAGE_SETTINGS, VIEW_SECURITY, MANAGE_SECURITY,
                    VIEW_LOGS, AUTHORIZE_RETURNS, AUTHORIZE_LOYALTY, MANAGE_SYSTEM_LOCK,
                    VIEW_WARRANTIES, MANAGE_WARRANTIES, VIEW_REPAIRS, MANAGE_REPAIRS,
                    VIEW_EMAIL_TEMPLATES, MANAGE_EMAIL_TEMPLATES, VIEW_MARKETING, MANAGE_MARKETING),
            UserRole.admin, List.of(
                    VIEW_DASHBOARD, VIEW_ITEMS, MANAGE_ITEMS, VIEW_CATEGORIES, MANAGE_CATEGORIES,
                    VIEW_INVENTORY, MANAGE_INVENTORY, VIEW_PROMOTIONS, MANAGE_PROMOTIONS,
                    VIEW_CUSTOMERS, MANAGE_CUSTOMERS, VIEW_BILLING, MANAGE_BILLING,
                    VIEW_REPORTS, EXPORT_REPORTS, VIEW_USERS, MANAGE_USERS,
                    VIEW_SETTINGS, MANAGE_SETTINGS, VIEW_SECURITY, MANAGE_SECURITY,
                    VIEW_LOGS, AUTHORIZE_RETURNS, AUTHORIZE_LOYALTY,
                    VIEW_WARRANTIES, MANAGE_WARRANTIES, VIEW_REPAIRS, MANAGE_REPAIRS,
                    VIEW_EMAIL_TEMPLATES, MANAGE_EMAIL_TEMPLATES, VIEW_MARKETING, MANAGE_MARKETING),
            UserRole.supervisor, List.of(
                    VIEW_DASHBOARD, VIEW_ITEMS, MANAGE_ITEMS, VIEW_CATEGORIES, MANAGE_CATEGORIES,
                    VIEW_INVENTORY, MANAGE_INVENTORY, VIEW_PROMOTIONS, MANAGE_PROMOTIONS,
                    VIEW_CUSTOMERS, MANAGE_CUSTOMERS, VIEW_BILLING, MANAGE_BILLING,
                    VIEW_REPORTS, EXPORT_REPORTS, AUTHORIZE_RETURNS, AUTHORIZE_LOYALTY,
                    VIEW_WARRANTIES, MANAGE_WARRANTIES, VIEW_REPAIRS, MANAGE_REPAIRS,
                    VIEW_EMAIL_TEMPLATES),
            UserRole.cashier, List.of(
                    VIEW_DASHBOARD, VIEW_ITEMS, VIEW_CATEGORIES, VIEW_INVENTORY,
                    VIEW_CUSTOMERS, MANAGE_CUSTOMERS, VIEW_BILLING, MANAGE_BILLING,
                    VIEW_WARRANTIES)
    );

    public static List<String> of(UserRole role) {
        return MATRIX.getOrDefault(role, List.of());
    }

    public static boolean has(UserRole role, String permission) {
        return of(role).contains(permission);
    }

    public static Set<String> all() {
        return Set.copyOf(MATRIX.values().stream().flatMap(List::stream).toList());
    }
}
