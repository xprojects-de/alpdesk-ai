### Vollständiges Beispiel

```xml
<object name="kaskade" handle="" dimension="" className="KM4">
  <param name="busAddress" value="12" />
  <param name="deviceCount" value="3" />
  <object name="kessel1" handle="" dimension="" className="BoilerChannel">
    <param name="deviceAddress" value="1" />
    <param name="maxFlowTemperature" value="75.0" />
  </object>
  <object name="kessel2" handle="" dimension="" className="BoilerChannel">
    <param name="deviceAddress" value="2" />
    <param name="maxFlowTemperature" value="75.0" />
  </object>
</object>
```

### KM4

#### XML-Vorlage

```xml
<object name="kM4" handle="" dimension="" className="KM4">
    <param name="busAddress" value="" />
    <param name="deviceCount" value="" />
</object>
```

#### Eigenschaften

| Property | Data Type | Config Type | Default Value | Description |
|---|---|---|---|---|
| `busAddress` | int | REQUIRED |  | The address of the module on the building bus, set with the rotary switch on its front |
| `deviceCount` | int | OPTIONAL | 4 | The number of boilers in the cascade (1 to 4) |

### BoilerChannel

#### XML-Vorlage

```xml
<object name="boilerChannel" handle="" dimension="" className="BoilerChannel">
    <param name="deviceAddress" value="" />
    <param name="maxFlowTemperature" value="" />
</object>
```

#### Eigenschaften

| Property | Data Type | Config Type | Default Value | Description |
|---|---|---|---|---|
| `deviceAddress` | int | REQUIRED |  | The boiler's address on the two-wire bus (1 = leading boiler) |
| `maxFlowTemperature` | double | OPTIONAL | 80.0 | The highest flow temperature the cascade asks of this boiler, in °C |
