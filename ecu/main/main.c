#include "ble.h"
#include "esp_log.h"
#include "esp_lvgl_port.h"
#include "i2c.h"
#include "lcd.h"
#include "ui.h"
#include <lvgl.h>

#define TAG "ECU"

const int BLE_CHECK_PERIOD_MS = 5000;
const int RPM_READ_PERIOD_MS = 100;

bool is_backlight_on = true;

void handle_twingo_click(lv_event_t *event) {
  lv_event_code_t code = lv_event_get_code(event);

  ESP_LOGI(TAG, "Twingo event: %d", code);

  // if (is_backlight_on) {
  //   lcd_backlight_on();
  // } else {
  //   lcd_backlight_off();
  // }
  // is_backlight_on = !is_backlight_on;
}

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

  // lcd_backlight_off();

  ESP_LOGI(TAG, "Initialize GPIO");
  gpio_init();

  ESP_LOGI(TAG, "Initialize LCD");
  ESP_ERROR_CHECK(lcd_init());

  ESP_LOGI(TAG, "Initialize UI");
  ui_init(handle_twingo_click);

  // lcd_backlight_on();

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
      if (tachometer_found) {
        read_rpm();
      } else if (i2c_check_device(I2C_NUM_0, TACHOMETER_I2C_ADDRESS)) {
        tachometer_found = true;
        ESP_LOGI(TAG, "Tachometer device found");
      }
      rpm_ticks = pdMS_TO_TICKS(RPM_READ_PERIOD_MS);
    }

    if (xTaskCheckForTimeOut(&ble_check_timeout, &ble_check_ticks) == pdTRUE) {
      ble_check_connection();
      ble_check_ticks = pdMS_TO_TICKS(BLE_CHECK_PERIOD_MS);
    }
  }
}
