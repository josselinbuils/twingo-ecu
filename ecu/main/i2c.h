#ifndef I2C_H
#define I2C_H

#include <driver/i2c.h>

#define ACK_CHECK_EN 0x1
#define ACK_VAL 0x0
#define NACK_VAL 0x1
#define READ_BIT I2C_MASTER_READ
#define TACHOMETER_I2C_ADDRESS 0x1

// I2C settings
#define I2C_SCL_PIN 9
#define I2C_SDA_PIN 8
#define I2C_NUM 0
#define I2C_FREQ_HZ 400000
#define I2C_TX_BUF_DISABLE 0
#define I2C_RX_BUF_DISABLE 0
#define I2C_TIMEOUT_MS 1000

bool i2c_check_device(i2c_port_t port, uint8_t address);

esp_err_t i2c_init();

esp_err_t i2c_master_read_slave(i2c_port_t i2c_num, uint8_t *data_rd, size_t size);

#endif
