package fr.paris1.miage.inf2.boutique;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;

/** Un produit dans une taille donnée, avec son stock. */
@Entity
public class Variante extends PanacheEntity {
    public String produit;
    public String marque;
    public String taille;
    public int prix;
    public int quantite;
}
