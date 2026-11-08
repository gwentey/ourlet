package fr.paris1.miage.inf2.boutique;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;

/** Le catalogue Ourlet, le même pour toutes les boutiques. Il est rangé en base au premier démarrage. */
@ApplicationScoped
public class Catalogue {

    @Transactional
    void remplir(@Observes StartupEvent demarrage) {
        if (Variante.count() > 0) return;
        ajouter("Hoodie oversize", 59, "S", "M", "L", "XL");
        ajouter("Sweat col rond", 45, "S", "M", "L");
        ajouter("T-shirt épais", 25, "XS", "S", "M", "L");
        ajouter("Jean large", 79, "38", "40", "42");
        ajouter("Baskets montantes", 110, "40", "41", "42", "43");
        ajouter("Doudoune courte", 120, "S", "M", "L");
        ajouter("Casquette", 19, "Unique");
        ajouter("Pantalon cargo", 65, "S", "M", "L");
    }

    private void ajouter(String produit, int prix, String... tailles) {
        for (String taille : tailles) {
            Variante v = new Variante();
            v.produit = produit; v.marque = "Ourlet"; v.taille = taille; v.prix = prix; v.quantite = 100000;
            v.persist();
        }
    }
}
