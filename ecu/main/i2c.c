#include <esp_log.h>
#include "i2c.h"

#include <hal/i2c_ll.h>

#define TAG "I2C"

bool i2c_check_device(const i2c_port_t port, const uint8_t address) {
    i2c_cmd_handle_t cmd = i2c_cmd_link_create();
    i2c_master_start(cmd);
    i2c_master_write_byte(cmd, address << 1 | I2C_MASTER_WRITE, true);
    i2c_master_stop(cmd);

    const esp_err_t ret = i2c_master_cmd_begin(port, cmd, 50 / portTICK_PERIOD_MS);
    i2c_cmd_link_delete(cmd);

    if (ret == ESP_OK) {
        return true;
    }
    if (ret != ESP_FAIL) {
        ESP_LOGE(TAG, "Error at address 0x%02X, error: %s", address, esp_err_to_name(ret));
    }
    return false;
}

esp_err_t i2c_init(void) {
    const int i2c_master_port = I2C_NUM;

    const i2c_config_t i2c_conf = {
        .mode = I2C_MODE_MASTER,
        .sda_io_num = I2C_SDA_PIN,
        .scl_io_num = I2C_SCL_PIN,
        .sda_pullup_en = GPIO_PULLUP_ENABLE,
        .scl_pullup_en = GPIO_PULLUP_ENABLE,
        .master.clk_speed = I2C_FREQ_HZ,
    };

    i2c_param_config(i2c_master_port, &i2c_conf);
    i2c_set_timeout(I2C_NUM_0, I2C_LL_MAX_TIMEOUT);

    return i2c_driver_install(i2c_master_port, i2c_conf.mode, 0, 0, 0);
}

esp_err_t i2c_master_read_slave(const i2c_port_t i2c_num, uint8_t *data_rd, const size_t size) {
    if (size == 0) {
        return ESP_OK;
    }
    i2c_cmd_handle_t cmd = i2c_cmd_link_create();
    i2c_master_start(cmd);
    i2c_master_write_byte(cmd, TACHOMETER_I2C_ADDRESS << 1 | READ_BIT, ACK_CHECK_EN);
    if (size > 1) {
        i2c_master_read(cmd, data_rd, size - 1, ACK_VAL);
    }
    i2c_master_read_byte(cmd, data_rd + size - 1, NACK_VAL);
    i2c_master_stop(cmd);
    const esp_err_t ret = i2c_master_cmd_begin(i2c_num, cmd, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);
    i2c_cmd_link_delete(cmd);
    return ret;
}
