package com.pmr.admin;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class InfoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        Button btnShare = findViewById(R.id.btnShareProgram);
        Button btnDescription = findViewById(R.id.btnShowDescription);

        btnShare.setOnClickListener(v -> {
            String url = "https://www.cqham.ru/forum/showthread.php/41895-Хочу-поделиться-программой/page9";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
            }
        });

        btnDescription.setOnClickListener(v -> {
            // Читаем opisanie.txt из assets и показываем в диалоге
            StringBuilder sb = new StringBuilder();
            try (InputStream is = getAssets().open("opisanie.txt");
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (IOException e) {
                sb.append("Ошибка чтения файла opisanie.txt");
            }

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Описание работы программы")
                    .setMessage(sb.toString())
                    .setPositiveButton("Закрыть", null)
                    .show();
        });
    }
}