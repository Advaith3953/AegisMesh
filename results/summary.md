# AegisMesh benchmark

Open-loop load of 300 req/s (x scenario factor), duration scale 0.60, simulated backends on loopback in one JVM. Compare arms **within** a scenario; absolute numbers depend on the machine. "After fault" covers the time from the first injected fault to the end.

## normal — healthy, identical backends

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 300 | 11.0 | 13.9 | 15.6 | 0.00 | 15.6 | 0.00 | 25.0 |
| rr-resilient | 300 | 10.6 | 13.4 | 18.1 | 0.00 | 18.1 | 0.00 | 25.0 |
| aegis-adaptive | 300 | 10.4 | 11.6 | 14.4 | 0.00 | 14.4 | 0.00 | 24.7 |

## uneven_capacity — backends have capacity 2/4/8/16

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 450 | 10.4 | 10.8 | 12.2 | 0.00 | 12.2 | 0.00 | 25.0 |
| rr-resilient | 450 | 10.4 | 11.8 | 16.8 | 0.00 | 16.8 | 0.00 | 25.0 |
| aegis-adaptive | 450 | 10.4 | 11.1 | 13.4 | 0.00 | 13.4 | 0.00 | 23.6 |

## high_latency — w1 gains +150 ms at 30%

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 271 | 10.6 | 480.9 | 901.4 | 9.60 | 920.3 | 13.72 | 13.0 |
| rr-resilient | 300 | 10.6 | 1001.6 | 1011.9 | 0.00 | 1013.0 | 0.00 | 11.3 |
| aegis-adaptive | 300 | 10.4 | 11.1 | 12.7 | 0.00 | 12.5 | 0.00 | 0.1 |

## backend_failure — w1 crashes at 30%, restarts at 70%

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 270 | 10.5 | 11.7 | 13.1 | 10.00 | 13.2 | 14.29 | 12.5 |
| rr-resilient | 300 | 10.5 | 11.4 | 17.4 | 0.00 | 22.8 | 0.00 | 8.7 |
| aegis-adaptive | 300 | 10.4 | 11.0 | 12.3 | 0.00 | 12.3 | 0.00 | 0.0 |

## cpu_saturation — w1 reports 95% CPU and is 4x slower at 30%

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 300 | 10.5 | 41.9 | 43.7 | 0.00 | 43.9 | 0.00 | 25.0 |
| rr-resilient | 300 | 10.5 | 41.7 | 43.8 | 0.00 | 43.8 | 0.00 | 25.0 |
| aegis-adaptive | 300 | 10.4 | 10.9 | 12.0 | 0.00 | 12.2 | 0.00 | 0.2 |

## packet_loss — w1 stalls 200 ms on half its requests at 30% (emulated loss)

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 300 | 10.5 | 321.9 | 463.1 | 0.00 | 481.0 | 0.00 | 25.0 |
| rr-resilient | 300 | 10.5 | 386.3 | 515.9 | 0.00 | 521.1 | 0.00 | 25.0 |
| aegis-adaptive | 300 | 10.4 | 11.1 | 25.4 | 0.00 | 26.5 | 0.00 | 0.5 |

## leader_failure — 3 gateways; the Raft leader dies at 40%

| arm | req/s | p50 ms | p95 ms | p99 ms | errors % | after-fault p99 ms | after-fault errors % | w1 share after % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rr-naive | 300 | 10.6 | 12.5 | 14.7 | 0.00 | 14.2 | 0.00 | 25.0 |
| rr-resilient | 300 | 10.5 | 11.9 | 14.3 | 0.00 | 14.1 | 0.00 | 25.0 |
| aegis-adaptive | 300 | 10.6 | 12.5 | 15.2 | 0.00 | 15.2 | 0.00 | 20.3 |

