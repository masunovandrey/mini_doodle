package com.masunov.task1.meeting;

import com.masunov.task1.slot.SlotEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "meeting")
public class MeetingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false, unique = true)
    private SlotEntity slot;

    @Column(nullable = false)
    private String title;

    private String description;

    @ElementCollection
    @CollectionTable(name = "meeting_participant", joinColumns = @JoinColumn(name = "meeting_id"))
    @OrderColumn(name = "participant_position")
    @Column(name = "participant", nullable = false)
    private List<String> participants = new ArrayList<>();

    protected MeetingEntity() {
    }

    MeetingEntity(SlotEntity slot, String title, String description, List<String> participants) {
        this.slot = slot;
        this.title = title;
        this.description = description;
        this.participants = new ArrayList<>(participants);
    }

    UUID getId() {
        return id;
    }

    SlotEntity getSlot() {
        return slot;
    }

    String getTitle() {
        return title;
    }

    String getDescription() {
        return description;
    }

    List<String> getParticipants() {
        return List.copyOf(participants);
    }
}
