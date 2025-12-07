#include <lvgl.h>
#include <sys/time.h>
#include "ble.h"
#include "esp_log.h"
#include "esp_lvgl_port.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "i2c.h"
#include "lcd.h"
#include "ui.h"

#define DEVELOP 0
#define TAG "ECU"

const int BLE_CHECK_PERIOD_MS = 5000;
const int RPM_READ_PERIOD_MS = 100;

unsigned long last_ble_check_time_ms = 0;
unsigned long last_rpm_read_time_ms = 0;

TaskHandle_t task_handle_ble_check = NULL;
TaskHandle_t task_handle_read_rpm = NULL;

unsigned long get_time_ms() {
    struct timeval time;
    gettimeofday(&time, NULL);
    return time.tv_sec * 1000 + time.tv_usec / 1000;
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

    if (DEVELOP) {
        ui_set_rpm(5500);
    }

    ESP_LOGI(TAG, "Initialize BLE");
    ble_init(ui_set_bluetooth_state, ui_set_current_music, ui_set_music_cover, ui_set_speed_limit);

    bool tachometer_found = false;
    unsigned long now_ms = 0;

    while (true) {
        now_ms = get_time_ms();

        if (now_ms - last_rpm_read_time_ms >= RPM_READ_PERIOD_MS) {
            last_rpm_read_time_ms = now_ms;

            if (tachometer_found) {
                uint8_t *buffer = malloc(4);
                const int ret = i2c_master_read_slave(I2C_NUM_0, buffer, 4);

                if (ret == ESP_ERR_TIMEOUT) {
                    ESP_LOGE(TAG, "I2C Timeout");
                } else if (ret == ESP_OK) {
                    const uint16_t rpm = buffer[0] | buffer[1] << 8;
                    ui_set_rpm(rpm);
                    ui_set_g_forces((int8_t) buffer[2], (int8_t) buffer[3]);
                    // ESP_LOGI(TAG, "rpm: %d", rpm);
                    // ESP_LOGI(TAG, "g forces: %d %d", (int8_t)buffer[2], (int8_t)buffer[3]);
                } else {
                    ESP_LOGI(TAG, "Master read slave error, IO not connected...");
                }
                free(buffer);
            } else if (i2c_check_device(I2C_NUM_0, TACHOMETER_I2C_ADDRESS)) {
                tachometer_found = true;
                ESP_LOGI(TAG, "Tachometer device found");
            }
        }

        if (now_ms - last_ble_check_time_ms >= BLE_CHECK_PERIOD_MS) {
            last_ble_check_time_ms = now_ms;
            ble_check_connection();
        }
    }
}
