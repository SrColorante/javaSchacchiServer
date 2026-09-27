package org.schacchi;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

public class Main {
    public static void main(String[] args) {
        // Esegue la creazione della GUI nel thread di Event Dispatching di Swing
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Server Scacchi");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(null); // Centra la finestra sullo schermo
            
            JLabel label = new JLabel("Server Scacchi - In Attesa di Connessioni...", SwingConstants.CENTER);
            frame.add(label);
            
            frame.setVisible(true);
        });
    }
}
