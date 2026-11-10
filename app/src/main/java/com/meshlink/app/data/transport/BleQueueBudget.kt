package com.meshlink.app.data.transport

/** Reserve event-channel capacity for incoming TLS and routing/ACK traffic. */
internal class BleQueueBudget {
    private var data = 0
    private var control = 0
    @Synchronized fun acquire(priority: Boolean): Boolean {
        if (priority) { if (control >= 16) return false; control++ }
        else { if (data >= 8) return false; data++ }
        return true
    }
    @Synchronized fun release(priority: Boolean) {
        if (priority) { check(control > 0); control-- } else { check(data > 0); data-- }
    }
    @Synchronized fun depth() = data + control
}
