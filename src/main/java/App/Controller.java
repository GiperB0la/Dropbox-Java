package App;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import Client.*;
import javafx.stage.FileChooser;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class Controller {
    @FXML
    public void initialize() throws IOException {
        client = new Client("127.0.0.1", 8888);
        client.setListCallback(files -> {
            Platform.runLater(() -> {
                fileList.getItems().setAll(files);
            });
        });
        new Thread(() -> {
            try {
                client.start();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
        fileList.setOnDragOver(event -> {
            if (event.getGestureSource() != fileList && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        fileList.setOnDragDropped(event -> {
            var db = event.getDragboard();
            boolean success = false;

            if (db.hasFiles()) {
                success = true;
                for (File file : db.getFiles()) {
                    client.sendUpload(file.getAbsolutePath());
                }
                client.sendList();
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    @FXML
    private void onUpload() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Upload Files to Server");
        List<File> files = fileChooser.showOpenMultipleDialog(null);

        if (files != null && !files.isEmpty()) {
            for (File file : files) {
                client.sendUpload(file.getAbsolutePath());
            }
        }
        client.sendList();
    }

    @FXML
    private void onDownload() {
        String selected = fileList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            client.sendDownload(selected);
            client.sendList();
        }
        else {
            System.out.println("[-] No file selected for download.");
        }
    }

    @FXML
    private void onDelete() {
        String selected = fileList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            client.sendDelete(selected);
            client.sendList();
        }
        else {
            System.out.println("[-] No file selected for deletion.");
        }
    }

    @FXML
    private void onRefresh() {
        client.sendList();
    }

    @FXML private ListView<String> fileList;
    private Client client;
}