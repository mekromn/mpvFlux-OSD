local mp = require("mp")

mp.register_event("start-file", function()
    mp.add_timeout(0.25, function()
        mp.osd_message("MPVFLUX LUA LOADED", 4)
    end)
end)

mp.add_forced_key_binding("MBTN_LEFT_DBL", "diag-left", function()
    mp.osd_message("MPVFLUX DOUBLE TAP: LEFT", 2)
end)

mp.add_forced_key_binding("MBTN_MID_DBL", "diag-center", function()
    mp.osd_message("MPVFLUX DOUBLE TAP: CENTER", 2)
end)

mp.add_forced_key_binding("MBTN_RIGHT_DBL", "diag-right", function()
    mp.osd_message("MPVFLUX DOUBLE TAP: RIGHT", 2)
end)
