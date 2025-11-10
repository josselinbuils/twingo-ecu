#include "ble.h"
#include "esp_log.h"
#include "esp_lvgl_port.h"
#include "i2c.h"
#include "lcd.h"
#include "ui.h"
#include <lvgl.h>

#define DEVELOP 0
#define TAG "ECU"

const int BLE_CHECK_PERIOD_MS = 5000;
const int RPM_READ_PERIOD_MS = 100;

void read_rpm() {
  uint8_t *buffer = (uint8_t *)malloc(2);
  int ret = i2c_master_read_slave(I2C_NUM_0, (uint8_t *)buffer, 2);

  if (ret == ESP_ERR_TIMEOUT) {
    ESP_LOGE(TAG, "I2C Timeout");
  } else if (ret == ESP_OK) {
    uint16_t rpm = buffer[0] | (buffer[1] << 8);
    ui_set_rpm(rpm);
    // ESP_LOGI(TAG, "rpm: %d", rpm);
  } else {
    ESP_LOGI(TAG, "Master read slave error, IO not connected...");
  }
  free(buffer);
}

void app_main() {
  ESP_LOGI(TAG, "Initialize I2C");
  ESP_ERROR_CHECK(i2c_init());

  ESP_LOGI(TAG, "Initialize GPIO");
  gpio_init();

  ESP_LOGI(TAG, "Initialize LCD");
  ESP_ERROR_CHECK(lcd_init());

  ESP_LOGI(TAG, "Initialize UI");
  lcd_backlight_off();
  ui_init();
  vTaskDelay(200 / portTICK_PERIOD_MS);
  lcd_backlight_on();

  if (DEVELOP && ui_lock()) {
    ui_set_rpm(5500);
    ui_unlock();
  }

  ESP_LOGI(TAG, "Initialize BLE");
  ble_init(ui_set_bluetooth_state, ui_set_current_music, ui_set_music_cover);

  if (!i2c_check_device(I2C_NUM_0, TACHOMETER_I2C_ADDRESS)) {
    ESP_LOGI(TAG, "Wait for tachometer device...");
  }

  bool tachometer_found = false;

  TimeOut_t ble_check_timeout;
  TickType_t ble_check_ticks = pdMS_TO_TICKS(BLE_CHECK_PERIOD_MS);

  TimeOut_t rpm_timeout;
  TickType_t rpm_ticks = pdMS_TO_TICKS(RPM_READ_PERIOD_MS);

  vTaskSetTimeOutState(&ble_check_timeout);
  vTaskSetTimeOutState(&rpm_timeout);

  while (true) {
    if (xTaskCheckForTimeOut(&rpm_timeout, &rpm_ticks) == pdTRUE) {
      if (ui_lock()) {
        if (tachometer_found) {
          read_rpm();
        } else if (i2c_check_device(I2C_NUM_0, TACHOMETER_I2C_ADDRESS)) {
          tachometer_found = true;
          ESP_LOGI(TAG, "Tachometer device found");
        }
        ui_unlock();
      }
      rpm_ticks = pdMS_TO_TICKS(RPM_READ_PERIOD_MS);
    }

    if (xTaskCheckForTimeOut(&ble_check_timeout, &ble_check_ticks) == pdTRUE) {
      if (ui_lock()) {
        ble_check_connection();
        ui_unlock();
      }
      ble_check_ticks = pdMS_TO_TICKS(BLE_CHECK_PERIOD_MS);
    }
  }
}
