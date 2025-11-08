#include "esp_log.h"
#include <driver/i2c.h>

#define ACK_CHECK_EN 0x1
#define ACK_VAL 0x0
#define NACK_VAL 0x1
#define READ_BIT I2C_MASTER_READ
#define TACHOMETER_I2C_ADDRESS 0x1

bool check_i2c_device(i2c_port_t port, uint8_t address);
esp_err_t i2c_master_read_slave(i2c_port_t i2c_num, uint8_t *data_rd, size_t size);
