// ... (импорты и остальной код без изменений до checkForUpdates)

    private void checkForUpdates() {
        Toast.makeText(this, "Проверка обновлений...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/rw6hhl/Admin-PMR-V9/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "Admin-PMR-App");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // ... (парсинг JSON для получения URL APK)
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    String tagName = json.getString("tag_name");
                    JSONArray assets = json.getJSONArray("assets");
                    String downloadUrl = null;

                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.getString("name");
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }

                    // V12.6: Извлекаем версию из тега (например, v123)
                    int latestVersionCode = 0;
                    try {
                        latestVersionCode = Integer.parseInt(tagName.replace("v", ""));
                    } catch (Exception ignored) {}

                    final String finalUrl = downloadUrl;
                    final int finalVersionCode = latestVersionCode;
                    if (downloadUrl != null && latestVersionCode > BuildConfig.VERSION_CODE) {
                        runOnUiThread(() -> showUpdateDialog(tagName, finalUrl));
                    } else {
                        runOnUiThread(() -> Toast.makeText(this, "У вас последняя версия", Toast.LENGTH_LONG).show());
                    }
                } else if (responseCode == 504) {
                    runOnUiThread(() -> showServerBusyDialog());
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "Ошибка сервера: " + responseCode, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка проверки: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // V12.6: Диалог при ошибке 504
    private void showServerBusyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Сервер перегружен (504)")
                .setMessage("Сервер GitHub временно недоступен. Вы можете скачать обновление вручную со страницы релизов.")
                .setPositiveButton("Открыть страницу", (dialog, which) -> {
                    String url = "https://github.com/rw6hhl/Admin-PMR-V9/releases/latest";
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    // ... (остальной код без изменений)