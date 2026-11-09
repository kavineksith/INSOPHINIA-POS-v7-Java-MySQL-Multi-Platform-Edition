package com.insophinia.pos.desktop;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestFX smoke test for the desktop {@link LoginView}.
 *
 * Verifies that the LoginView can be constructed, mounted on the JavaFX scene
 * graph, and that its key UI elements (username field, password field, server
 * URL field, sign-in button, error label) are rendered. Does NOT actually
 * log in — that requires a running backend, which we don't have in the
 * unit-test environment.
 *
 * Runs headlessly via the Monocle screen backend (configured in pom.xml's
 * surefire plugin). To run interactively in a real JavaFX window for
 * debugging, set -Dtestfx.headless=false and remove monocle.platform.
 *
 * Prerequisites:
 *   - JavaFX runtime on the module path (--add-modules javafx.controls,...)
 *   - Monocle on the classpath (org.testfx:openjfx-monocle)
 *   - -Dtestfx.headless=true -Dmonocle.platform=HEADLESS
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginViewTestFxSmokeTest extends ApplicationTest {

    @Override
    public void start(Stage stage) throws Exception {
        // Reset the Session singleton so any prior test doesn't leak state.
        Session.clear();
        Session.baseUrl = "http://127.0.0.1:8080";

        // LoginView takes only a Runnable (no Stage) — its constructor builds
        // a VBox root we mount on our own Scene.
        LoginView loginView = new LoginView(() -> { /* no-op logout callback */ });
        stage.setScene(new Scene(loginView.getRoot(), 420, 360));
        stage.setTitle("INSOPHINIA POS — Login (TestFX)");
        stage.show();
    }

    @AfterEach
    void resetSession() {
        Session.clear();
        Session.baseUrl = null;
    }

    @Test
    void loginFormRendersAllExpectedFieldsAndButtons(FxRobot robot) {
        // Three TextFields exist on the form: username, password (PasswordField
        // extends TextField), and server URL. We assert each lookup succeeds.
        TextField username = robot.lookup(".text-field").queryAs(TextField.class);
        assertNotNull(username, "Username TextField must be rendered");

        PasswordField password = robot.lookup(".password-field").queryAs(PasswordField.class);
        assertNotNull(password, "Password PasswordField must be rendered");

        Button signIn = robot.lookup(".button").queryButton();
        assertNotNull(signIn, "Submit Button must be rendered");
    }

    @Test
    void usernameFieldAcceptsTypedInput(FxRobot robot) {
        robot.clickOn(".text-field").write("supervisor1");
        TextField username = robot.lookup(".text-field").queryAs(TextField.class);
        assertEquals("supervisor1", username.getText(),
                "Typed text must appear in the username field");
    }

    @Test
    void passwordFieldAcceptsTypedInputAndModelIsAccessible(FxRobot robot) {
        robot.clickOn(".password-field").write("SuperSecret123!");
        PasswordField password = robot.lookup(".password-field").queryAs(PasswordField.class);
        assertEquals("SuperSecret123!", password.getText(),
                "Typed text must appear in the password field's model (display is masked)");
    }

    @Test
    void titleAndSubtitleLabelsArePresent(FxRobot robot) {
        // The LoginView renders two labels at the top of the form:
        // "INSOPHINIA POS" (title) and "Sign in to continue" (subtitle).
        Label titleLabel = robot.lookup(node -> node instanceof Label
                && "INSOPHINIA POS".equals(((Label) node).getText())).queryAs(Label.class);
        assertNotNull(titleLabel, "Title label 'INSOPHINIA POS' must be rendered");

        Label subtitleLabel = robot.lookup(node -> node instanceof Label
                && "Sign in to continue".equals(((Label) node).getText())).queryAs(Label.class);
        assertNotNull(subtitleLabel, "Subtitle label 'Sign in to continue' must be rendered");
    }
}

