package com.insophinia.pos.desktop;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class PosDesktopApp extends Application {

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("INSOPHINIA POS");
        showLogin();
        stage.show();
    }

    public void showLogin() {
        LoginView login = new LoginView(this::showMain);
        Scene scene = new Scene(login.getRoot(), 420, 360);
        stage.setScene(scene);
        stage.centerOnScreen();
    }

    public void showMain() {
        MainView main = new MainView(this::showLogin);
        Scene scene = new Scene(main.getRoot(), 1180, 760);
        stage.setScene(scene);
        stage.centerOnScreen();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
