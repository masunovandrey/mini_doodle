package com.masunov.task1.meeting;

import java.util.List;

record MeetingRequest(String title, String description, List<String> participants) {
}
