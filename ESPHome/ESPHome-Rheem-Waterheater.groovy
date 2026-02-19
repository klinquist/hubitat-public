/**
 *  MIT License
 *  Copyright 2024 Kris Linquist (kris@linquist.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
metadata {
    definition(
        name: 'ESPHome Rheem Water Heater (Feb 2026)',
        namespace: 'esphome',
        author: 'Kris Linquist',
        singleThreaded: true,
        importUrl: 'https://raw.githubusercontent.com/klinquist/hubitat-public/esphome-econet/ESPHome/ESPHome-Rheem-Waterheater.groovy') {

        capability 'Refresh'
        capability 'Initialize'
        capability 'SignalStrength'
        capability 'Switch'
        capability 'Thermostat'
        capability 'TemperatureMeasurement'
        capability 'ThermostatHeatingSetpoint'
        capability 'ThermostatOperatingState'
        
        command 'setWaterHeaterMode', [[name:'Mode*','type':'ENUM','description':'Mode','constraints':['Heat Pump', 'Energy Saver', 'High Demand', 'Electric/Gas', 'Off']]]
        command 'setVacationMode', [[name:'VacationMode*','type':'ENUM','description':'VacationMode','constraints':['Off', 'Permanent']]]
        command 'resetAlarms'
        command 'resetAlarmHistory'

        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
        attribute 'waterHeaterMode', 'enum', ['Heat Pump', 'Energy Saver', 'High Demand', 'Electric/Gas', 'Off']
        attribute 'lowerTankTemperature', 'number'
        attribute 'upperTankTemperature', 'number'
        attribute 'powerWatts', 'number'
        attribute 'lowerHeatingElementRuntime', 'number'
        attribute 'upperHeatingElementRuntime', 'number'
        attribute 'ambientTemperature', 'number'
        attribute 'vacationMode', 'enum', ['Off', 'Timed', 'Permanent']
        attribute 'heatingElementState', 'enum', ['Off', 'On']
        attribute 'compressorState', 'string'
        attribute 'thermostatHeatingSetpoint', 'number'

        
        attribute 'hotWaterAvailabilityPercent', 'number'
        attribute 'evaporatorTemperature', 'number'
        attribute 'suctionTemperature', 'number'
        attribute 'dischargeTemperature', 'number'
        attribute 'compressorRuntime', 'number'
        attribute 'fanLowSpeedRuntime', 'number'
        attribute 'fanHighSpeedRuntime', 'number'
        attribute 'energyKWh', 'number'
        attribute 'acCurrentRms', 'number'
        attribute 'expansionValvePosition', 'number'
        attribute 'fanSpeed', 'string'
        attribute 'unitType', 'string'
        attribute 'econetMode', 'string'
        attribute 'activeAlerts', 'number'
        attribute 'microcontrollerConnected', 'enum', ['true', 'false']
        attribute 'alarm1', 'string'
        attribute 'alarm2', 'string'
        attribute 'alarm3', 'string'
        attribute 'alarm4', 'string'
        attribute 'alarmHistory1', 'string'
        attribute 'alarmHistory2', 'string'
        attribute 'softwareVersion', 'string'

    }

    preferences {
        input name: 'ipAddress',    // required setting for API library
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'password',     // optional setting for API library
                type: 'text',
                title: 'Device Password <i>(if required)</i>',
                required: false

        input name: 'logEnable',    // if enabled the library will log debug details
                type: 'bool',
                title: 'Enable Debug Logging',
                required: false,
                defaultValue: false

        input name: 'logTextEnable',
              type: 'bool',
              title: 'Enable state update logging',
              required: false,
              defaultValue: true

        input name: 'vacationModeSwitch',
              type: 'bool',
              title: 'Should on/off switch be used to toggle vacation mode?',
              required: false,
              defaultValue: true

        input name: 'celsius',
              type: 'bool',
              title: 'Use celsius?',
              required: false,
              defaultValue: false
    }
}

import com.hubitat.app.ChildDeviceWrapper

def convertFtoC(temp) {
    return (temp - 32) * 5 / 9
}

def convertCtoF(temp) {
    return temp * 9 / 5 + 32
}

def roundToNearestTenth(value) {
    return Math.round(value * 10) / 10.0
}

def roundTo(value, decimals) {
    return new BigDecimal(value.toString()).setScale(decimals as int, java.math.RoundingMode.HALF_UP).doubleValue()
}

private void initializeThermostatAttributes() {
    String modes = '["off","heat","auto","emergency heat"]'
    if (device.currentValue('supportedThermostatModes') != modes) {
        updateAttribute('supportedThermostatModes', modes)
    }
    if (device.currentValue('supportedThermostatFanModes') != '["auto"]') {
        updateAttribute('supportedThermostatFanModes', '["auto"]')
    }
    if (device.currentValue('thermostatFanMode') != 'auto') {
        updateAttribute('thermostatFanMode', 'auto')
    }
}

private String waterHeaterModeToThermostatMode(final String waterHeaterMode) {
    switch (waterHeaterMode) {
        case 'Off':
            return 'off'
        case 'Energy Saver':
            return 'auto'
        case 'High Demand':
            return 'emergency heat'
        case 'Heat Pump':
        case 'Electric/Gas':
            return 'heat'
        default:
            return 'heat'
    }
}

private String thermostatModeToWaterHeaterMode(final String thermostatMode) {
    switch (thermostatMode) {
        case 'off':
            return 'Off'
        case 'auto':
            return 'Energy Saver'
        case 'heat':
            return 'Heat Pump'
        case 'emergency heat':
            return 'High Demand'
        default:
            return null
    }
}

private String normalizeEntityId(final Map message) {
    String objectId = (message?.objectId ?: '').toString().trim()
    if (objectId) {
        return objectId
    }
    String name = (message?.name ?: '').toString().trim()
    if (!name) {
        return ''
    }
    String normalized = name.toLowerCase()
            .replaceAll(/[^a-z0-9]+/, '_')
            .replaceAll(/_+/, '_')
            .replaceAll(/^_+|_+$/, '')
    return normalized
}

public void initialize() {
    // API library command to open socket to device, it will automatically reconnect if needed
    openSocket()
    initializeThermostatAttributes()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

public void installed() {
    log.info "${device} driver installed"
}

public void logsOff() {
    espHomeSubscribeLogs(LOG_LEVEL_INFO, false) // disable device logging
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

public void refresh() {
    log.info "${device} refresh"
    state.clear()
    state.requireRefresh = true
    espHomeDeviceInfoRequest()
}

public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

public void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

// driver commands
public void on() {
    if (vacationModeSwitch) {
        setVacationMode('Off')
    } else {
        setWaterHeaterMode('Eco Mode')
    }
}

public void off() {
    if (vacationModeSwitch) {
        setVacationMode('Permanent')
    } else {
        setWaterHeaterMode('Off')
    }
}

public void setWaterHeaterMode(String value) {
    String requestedMode = value
    if (value == 'Energy Saver') {
        value = 'Eco Mode'
    }
    String currentMode = device.currentValue('waterHeaterMode')
    if (currentMode == requestedMode || (currentMode == 'Energy Saver' && value == 'Eco Mode')) {
        if (logTextEnable) { log.info "${device} setWaterHeaterMode to ${requestedMode} skipped (already set)" }
        return
    }
    if (logTextEnable) { log.info "${device} setWaterHeaterMode to ${requestedMode}" }
    if (!state.climate) {
        log.warn "${device} setWaterHeaterMode requested but climate key is not available yet; try Refresh first"
        return
    }
    espHomeClimateCommand(key: state.climate as Long, customPreset: value)
}

public void setThermostatMode(String thermostatMode) {
    String normalizedMode = (thermostatMode ?: '').trim().toLowerCase()
    String waterHeaterMode = thermostatModeToWaterHeaterMode(normalizedMode)
    if (!waterHeaterMode) {
        log.warn "${device} setThermostatMode to ${thermostatMode} is not supported"
        return
    }
    if (logTextEnable) { log.info "${device} setThermostatMode to ${normalizedMode} (mapped to ${waterHeaterMode})" }
    setWaterHeaterMode(waterHeaterMode)
}

public void auto() {
    setThermostatMode('auto')
}

public void heat() {
    setThermostatMode('heat')
}

public void emergencyHeat() {
    setThermostatMode('emergency heat')
}

public void cool() {
    log.warn "${device} cool mode is not supported"
}

public void setCoolingSetpoint(float value) {
    log.warn "${device} setCoolingSetpoint is not supported"
}

public void setThermostatFanMode(String thermostatFanMode) {
    log.warn "${device} setThermostatFanMode is not supported"
}

public void fanAuto() {
    updateAttribute('thermostatFanMode', 'auto')
}

public void fanOn() {
    log.warn "${device} fanOn is not supported"
}

public void fanCirculate() {
    log.warn "${device} fanCirculate is not supported"
}

public void setVacationMode(String value) {
    if (logTextEnable) { log.info "${device} setVacationMode to ${value} using key ${state.vacation}" }
    if (device.currentValue('vacationMode') == value) {
        if (logTextEnable) { log.info "${device} setVacationMode to ${value} skipped (already set)" }
        return
    }
    if (!state.vacation) {
        log.warn "${device} setVacationMode requested but vacation key is not available yet; try Refresh first"
        return
    }
    espHomeSelectCommand(key: state.vacation as Long, state: value)
}

public void setHeatingSetpoint(float value) {

    if (celsius){
        if (value < 43.33 || value > 60){
            log.error "${device} setThermostatHeatingSetpoint to ${value} is out of range (44-60C)"
            return
        }
        valueC = value
    } else { 
        if (value < 110 || value > 140){
            log.error "${device} setThermostatHeatingSetpoint to ${value} is out of range (110-140)"
            return
        }
        valueC = ((value - 32) / 1.8) as float
    }

    if (logTextEnable) { log.info "${device} setThermostatHeatingSetpoint to ${value} (${valueC} celsius)" }
    //ESPHome expects Celsius
    if (!state.climate) {
        log.warn "${device} setHeatingSetpoint requested but climate key is not available yet; try Refresh first"
        return
    }
    BigDecimal currentSetpoint = device.currentValue('thermostatHeatingSetpoint') as BigDecimal
    if (currentSetpoint != null && Math.abs((currentSetpoint as double) - (value as double)) < 0.1d) {
        if (logTextEnable) { log.info "${device} setHeatingSetpoint to ${value} skipped (already set)" }
        return
    }
    espHomeClimateCommand(key: state.climate as Long, targetTemperature: valueC)
}

public void resetAlarms() {
    if (!state.alarmReset) {
        log.warn "${device} resetAlarms requested but alarm reset button entity is not available"
        return
    }
    if (logTextEnable) { log.info "${device} resetAlarms" }
    espHomeButtonCommand(key: state.alarmReset as Long)
}

public void resetAlarmHistory() {
    if (!state.alarmHistoryReset) {
        log.warn "${device} resetAlarmHistory requested but alarm history reset button entity is not available"
        return
    }
    if (logTextEnable) { log.info "${device} resetAlarmHistory" }
    espHomeButtonCommand(key: state.alarmHistoryReset as Long)
}


// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            
            //Each sensor has a unique key that is used to send commands to the device (also used to interpret received state messages)
            //These are received as a flood of messages when the device is first connected and are used to populate the settings

            String entityId = normalizeEntityId(message)
            switch (entityId) {
                case 'power':
                    state['power'] = message.key
                    break
                case  'lower_tank_temperature':
                    state['lowerTankTemperature'] = message.key
                    break
                case 'upper_tank_temperature':
                    state['upperTankTemperature'] = message.key
                    break
                case 'lower_heating_element_runtime':
                    state['lowerHeatingElementRuntime'] = message.key
                    break
                case 'upper_heating_element_runtime':
                    state['upperHeatingElementRuntime'] = message.key
                    break
                case 'hot_water':
                    state['hotWater'] = message.key
                    break
                case 'ambient_temperature':
                    state['ambientTemperature'] = message.key
                    break
                case 'vacation':
                    state['vacation'] = message.key
                    break
                case 'mode':
                    state['mode'] = message.key
                    break
                case 'heating_element_state':
                    state['heatingElementState'] = message.key
                    break
                case 'compressor':
                    state['compressorState'] = message.key
                    break                    
                case 'evaporator_temperature':
                    state['evaporatorTemperature'] = message.key
                    break
                case 'suction_temperature':
                    state['suctionTemperature'] = message.key
                    break
                case 'discharge_temperature':
                    state['dischargeTemperature'] = message.key
                    break
                case 'compressor_runtime':
                    state['compressorRuntime'] = message.key
                    break
                case 'fan_low_speed_runtime':
                    state['fanLowSpeedRuntime'] = message.key
                    break
                case 'fan_high_speed_runtime':
                    state['fanHighSpeedRuntime'] = message.key
                    break
                case 'energy':
                    state['energyKWh'] = message.key
                    break
                case 'ac_current_rms':
                    state['acCurrentRms'] = message.key
                    break
                case 'expansion_valve_current_position':
                    state['expansionValvePosition'] = message.key
                    break
                case 'fan_speed':
                    state['fanSpeed'] = message.key
                    break
                case 'unit_type':
                    state['unitType'] = message.key
                    break
                case 'wifi_signal_strength':
                    state['signalStrength'] = message.key
                    break
                case 'active_alerts':
                    state['activeAlerts'] = message.key
                    break
                case 'microcontroller_connected':
                    state['microcontrollerConnected'] = message.key
                    break
                case 'alarm_1':
                    state['alarm1'] = message.key
                    break
                case 'alarm_2':
                    state['alarm2'] = message.key
                    break
                case 'alarm_3':
                    state['alarm3'] = message.key
                    break
                case 'alarm_4':
                    state['alarm4'] = message.key
                    break
                case 'alarm_history_1':
                    state['alarmHistory1'] = message.key
                    break
                case 'alarm_history_2':
                    state['alarmHistory2'] = message.key
                    break
                case 'software_version_number':
                    state['softwareVersion'] = message.key
                    break
                case 'alarm_reset':
                    state['alarmReset'] = message.key
                    break
                case 'alarm_history_reset':
                    state['alarmHistoryReset'] = message.key
                    break
                default:
                    if (logEnable) {
                        log.debug "Skipping storing key ID for : ${message.objectId ?: ''} (${message.name ?: ''}) (normalized: ${entityId})"
                    }
            }
            break

        case 'state':
            
            // Signal Strength
            if (state.signalStrength as Long == message.key && message.hasState) {
                Integer rssi = Math.round(message.state as Float)
                String unit = 'dBm'
                if (device.currentValue('rssi') != rssi) {
                    descriptionText = "${device} rssi is ${rssi}"
                    sendEvent(name: 'rssi', value: rssi, unit: unit, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }
                return
            }

            if (state.activeAlerts as Long == message.key && message.hasState) {
                Integer count = Math.round(message.state as Float)
                if (device.currentValue('activeAlerts') != count) {
                    updateAttribute('activeAlerts', count)
                }
                return
            }

            if (state.microcontrollerConnected as Long == message.key && message.hasState) {
                String connected = (message.state == true) ? 'true' : 'false'
                if (device.currentValue('microcontrollerConnected') != connected) {
                    updateAttribute('microcontrollerConnected', connected)
                }
                return
            }

            //All the other sensors

            if (state.power as Long == message.key && message.hasState) {
                Integer power = Math.round(message.state as Float)
                if (device.currentValue('powerWatts') != power) {
                    updateAttribute('powerWatts', power, 'W')
                }
                return
            }

            if (state.evaporatorTemperature as Long == message.key && message.hasState) {
                Double temperature = message.state
                if (celsius) temperature = convertFtoC(temperature)
                temperature = roundToNearestTenth(temperature)
                if (device.currentValue('evaporatorTemperature') != temperature) {
                    updateAttribute('evaporatorTemperature', temperature, celsius == true ? 'C' : 'F')
                }
                return
            }

            if (state.suctionTemperature as Long == message.key && message.hasState) {
                Double temperature = message.state
                if (celsius) temperature = convertFtoC(temperature)
                temperature = roundToNearestTenth(temperature)
                if (device.currentValue('suctionTemperature') != temperature) {
                    updateAttribute('suctionTemperature', temperature, celsius == true ? 'C' : 'F')
                }
                return
            }

            if (state.dischargeTemperature as Long == message.key && message.hasState) {
                Double temperature = message.state
                if (celsius) temperature = convertFtoC(temperature)
                temperature = roundToNearestTenth(temperature)
                if (device.currentValue('dischargeTemperature') != temperature) {
                    updateAttribute('dischargeTemperature', temperature, celsius == true ? 'C' : 'F')
                }
                return
            }

            if (state.lowerTankTemperature as Long == message.key && message.hasState) {
                Double temperature = message.state
                if (celsius) temperature = convertFtoC(temperature)
                temperature = roundToNearestTenth(temperature)
                if (device.currentValue('lowerTankTemperature') != temperature) {
                    updateAttribute('lowerTankTemperature', temperature, celsius == true ? 'C' : 'F')
                }
                return
            }

            if (state.upperTankTemperature as Long == message.key && message.hasState) {
                Double temperature = message.state
                if (celsius) temperature = convertFtoC(temperature)
                temperature = roundToNearestTenth(temperature)
                if (device.currentValue('upperTankTemperature') != temperature) {
                    updateAttribute('upperTankTemperature', temperature, celsius == true ? 'C' : 'F')
                    updateAttribute('temperature', temperature, celsius == true ? 'C' : 'F') //Set this attribute so the "TemperatureMeasurement" capability works
                }
                return
            }

            if (state.upperHeatingElementRuntime as Long == message.key && message.hasState) {
                Integer runtime = Math.round(message.state as Float)
                if (device.currentValue('upperHeatingElementRuntime') != runtime) {
                    updateAttribute('upperHeatingElementRuntime', runtime, 'hours')
                }
                return
            }

            if (state.lowerHeatingElementRuntime as Long == message.key && message.hasState) {
                Integer runtime = Math.round(message.state as Float)
                if (device.currentValue('lowerHeatingElementRuntime') != runtime) {
                    updateAttribute('lowerHeatingElementRuntime', runtime, 'hours')
                }
                return
            }

            if (state.hotWater as Long == message.key && message.hasState) {
                Integer percent = Math.round(message.state as Float)
                if (device.currentValue('hotWaterAvailabilityPercent') != percent) {
                    updateAttribute('hotWaterAvailabilityPercent', percent, '%')
                }
                return
            }

            if (state.ambientTemperature as Long == message.key && message.hasState) {
                Double temperature = message.state
                if (celsius) temperature = convertFtoC(temperature)
                temperature = roundToNearestTenth(temperature)
                if (device.currentValue('ambientTemperature') != temperature) {
                    updateAttribute('ambientTemperature', temperature, celsius == true ? 'C' : 'F')
                }
                return
            }

            if (state.compressorRuntime as Long == message.key && message.hasState) {
                Double hours = roundTo(message.state as Double, 2)
                if (device.currentValue('compressorRuntime') != hours) {
                    updateAttribute('compressorRuntime', hours, 'hours')
                }
                return
            }

            if (state.fanLowSpeedRuntime as Long == message.key && message.hasState) {
                Double hours = roundTo(message.state as Double, 2)
                if (device.currentValue('fanLowSpeedRuntime') != hours) {
                    updateAttribute('fanLowSpeedRuntime', hours, 'hours')
                }
                return
            }

            if (state.fanHighSpeedRuntime as Long == message.key && message.hasState) {
                Double hours = roundTo(message.state as Double, 2)
                if (device.currentValue('fanHighSpeedRuntime') != hours) {
                    updateAttribute('fanHighSpeedRuntime', hours, 'hours')
                }
                return
            }

            if (state.energyKWh as Long == message.key && message.hasState) {
                Double energy = roundTo(message.state as Double, 3)
                if (device.currentValue('energyKWh') != energy) {
                    updateAttribute('energyKWh', energy, 'kWh')
                }
                return
            }

            if (state.acCurrentRms as Long == message.key && message.hasState) {
                Double amps = roundTo(message.state as Double, 3)
                if (device.currentValue('acCurrentRms') != amps) {
                    updateAttribute('acCurrentRms', amps, 'A')
                }
                return
            }

            if (state.expansionValvePosition as Long == message.key && message.hasState) {
                Double percent = roundTo(message.state as Double, 1)
                if (device.currentValue('expansionValvePosition') != percent) {
                    updateAttribute('expansionValvePosition', percent, '%')
                }
                return
            }

            if (state.vacation as Long == message.key && message.hasState) {
                if (device.currentValue('vacationMode') != message.state) {
                    if (vacationModeSwitch){
                        if (message.state == 'Off'){
                            updateAttribute('switch', 'on')
                        } else {
                            updateAttribute('switch', 'off')
                        }
                    }
                    updateAttribute('vacationMode', message.state)
                }
                return
            }

            if (state.mode as Long == message.key && message.hasState) {
                if (device.currentValue('econetMode') != message.state) {
                    updateAttribute('econetMode', message.state)
                }
                return
            }

            if (state.heatingElementState as Long == message.key && message.hasState) {
                if (device.currentValue('heatingElementState') != message.state) {
                    updateAttribute('heatingElementState', message.state)
                }
                return
            }

            if (state.fanSpeed as Long == message.key && message.hasState) {
                if (device.currentValue('fanSpeed') != message.state) {
                    updateAttribute('fanSpeed', message.state)
                }
                return
            }

            if (state.unitType as Long == message.key && message.hasState) {
                if (device.currentValue('unitType') != message.state) {
                    updateAttribute('unitType', message.state)
                }
                return
            }

            if (state.alarm1 as Long == message.key && message.hasState) {
                if (device.currentValue('alarm1') != message.state) {
                    updateAttribute('alarm1', message.state)
                }
                return
            }

            if (state.alarm2 as Long == message.key && message.hasState) {
                if (device.currentValue('alarm2') != message.state) {
                    updateAttribute('alarm2', message.state)
                }
                return
            }

            if (state.alarm3 as Long == message.key && message.hasState) {
                if (device.currentValue('alarm3') != message.state) {
                    updateAttribute('alarm3', message.state)
                }
                return
            }

            if (state.alarm4 as Long == message.key && message.hasState) {
                if (device.currentValue('alarm4') != message.state) {
                    updateAttribute('alarm4', message.state)
                }
                return
            }

            if (state.alarmHistory1 as Long == message.key && message.hasState) {
                if (device.currentValue('alarmHistory1') != message.state) {
                    updateAttribute('alarmHistory1', message.state)
                }
                return
            }

            if (state.alarmHistory2 as Long == message.key && message.hasState) {
                if (device.currentValue('alarmHistory2') != message.state) {
                    updateAttribute('alarmHistory2', message.state)
                }
                return
            }

            if (state.softwareVersion as Long == message.key && message.hasState) {
                if (device.currentValue('softwareVersion') != message.state) {
                    updateAttribute('softwareVersion', message.state)
                }
                return
            }

            if (state.compressorState as Long == message.key && message.hasState) {
                if (message.state == false){
                    message.state = 'Not Running'
                } else {
                    message.state = 'Running'
                }
                if (device.currentValue('compressorState') != message.state) {
                    updateAttribute('compressorState', message.state)
                    if (message.state == 'Running'){
                        updateAttribute('thermostatOperatingState', 'heating')
                    } else {
                        updateAttribute('thermostatOperatingState', 'idle')
                    }
                }
                return
            }            


            if (message.platform == 'climate') {
                state['climate'] = message.key
                if (message.targetTemperature) {
                    //The value that comes in from ESPHome is in Celsius
                    Double temperatureF = roundToNearestTenth(convertCtoF(message.targetTemperature.toDouble()))
                    Double temperatureC = roundToNearestTenth(message.targetTemperature.toDouble())
                    if (celsius) {
                        temperature = temperatureC
                    } else {
                        temperature = temperatureF
                    }
                    
                    if (device.currentValue('thermostatHeatingSetpoint') != temperature) {
                        updateAttribute('thermostatHeatingSetpoint', temperature, celsius == true ? 'C' : 'F')
                        updateAttribute('heatingSetpoint', temperature, celsius == true ? 'C' : 'F') //Set this attribute so the "ThermostatHeatingSetpoint" capability works
                        updateAttribute('thermostatSetpoint', temperature, celsius == true ? 'C' : 'F')
                    }
                }

                if (message.customPreset) {                    
                    initializeThermostatAttributes()
                    String customPreset = message.customPreset
                    if (customPreset == 'Eco Mode'){
                        customPreset = 'Energy Saver'
                    }
                    if (device.currentValue('waterHeaterMode') != customPreset) {
                        if (customPreset == 'Off'){
                            if (!vacationModeSwitch){
                                updateAttribute('switch', 'off')
                            }
                        } else {
                            if (!vacationModeSwitch) {
                                updateAttribute('switch', 'on')
                            }
                        }
                        updateAttribute('waterHeaterMode', customPreset)
                    }
                    String thermostatMode = waterHeaterModeToThermostatMode(customPreset)
                    if (device.currentValue('thermostatMode') != thermostatMode) {
                        updateAttribute('thermostatMode', thermostatMode)
                    }
                }

                return
            }

    }
}


/**
 * Update the specified device attribute with the specified value and log if changed
 * @param attribute name of the attribute
 * @param value value of the attribute
 * @param unit unit of the attribute
 * @param type type of the attribute
 */
private void updateAttribute(final String attribute, final Object value, final String unit = null, final String type = null) {
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    sendEvent(name: attribute, value: value, unit: unit, type: type, descriptionText: descriptionText)
    if (logTextEnable) { log.info descriptionText }
    
}



// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
