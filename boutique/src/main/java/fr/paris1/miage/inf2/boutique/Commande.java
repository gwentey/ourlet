package fr.paris1.miage.inf2.boutique;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import java.time.Instant;

@Entity
public class Commande extends PanacheEntity {
    public Long varianteId;
    public String client;
    public Instant creeLe = Instant.now();
    public String statut = "CONFIRMEE";
}
