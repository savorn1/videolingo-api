package com.example.videolingo.dto;

import java.time.OffsetDateTime;
import lombok.Data;

// When to enable a disabled video by itself. With its offset (the browser sends UTC),
// so it's turned into the server's own time correctly; null clears the schedule.
@Data
public class ScheduleVideoRequest {

    private OffsetDateTime publishAt;
}
