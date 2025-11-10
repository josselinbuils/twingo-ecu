#ifndef BLE_H
#define BLE_H

#include "esp_log.h"
#include "nvs_flash.h"
#include "console/console.h"
#include "external/esp_central.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"

struct ble_hs_adv_fields;
struct ble_gap_conn_desc;
struct ble_hs_cfg;
union ble_store_value;
union ble_store_key;

int ble_check_connection();
void ble_init(
  void (*bluetooth_state_callback)(bool enabled),
  void (*current_music_callback)(char *current_music),
  void (*music_cover_callback)(uint8_t *music_cover_map),
  void (*speed_limit_callback)(char *speed_limit)
);

#endif
