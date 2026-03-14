package com.example.peekblock;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.os.Build;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.N)
public class PeekBlockTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile == null) return;

        if (PeekBlockService.isServiceRunning) {
            stopPeekBlockService();
            tile.setState(Tile.STATE_INACTIVE);
        } else {
            startPeekBlockService();
            tile.setState(Tile.STATE_ACTIVE);
        }
        tile.updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(PeekBlockService.isServiceRunning ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    private void startPeekBlockService() {
        Intent intent = new Intent(this, PeekBlockService.class);
        intent.setAction(PeekBlockService.ACTION_START_DETECTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopPeekBlockService() {
        Intent intent = new Intent(this, PeekBlockService.class);
        intent.setAction(PeekBlockService.ACTION_STOP_DETECTION);
        startService(intent);
    }
}