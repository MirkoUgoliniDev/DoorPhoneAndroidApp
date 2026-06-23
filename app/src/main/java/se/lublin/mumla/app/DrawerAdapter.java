

package se.lublin.mumla.app;


import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import se.lublin.mumla.R;




public class DrawerAdapter extends ArrayAdapter<DrawerAdapter.DrawerRow> {

    /**
     * Provides context for the drawer module.
     */
    public interface DrawerDataProvider {

        /**
         * @return true if connected, false otherwise.
         */
        public boolean isConnected();
        /**
         * @return The name of the connected server. If not connected, then null.
         */
        public String getConnectedServerName();
    }


    // Drawer rows, integer value is id
    public static final int HEADER_CONNECTED_SERVER = 0;
    public static final int ITEM_SERVER = 1;
    public static final int ITEM_PINNED_CHANNELS = 2;
    public static final int ITEM_INFO = 3;
    public static final int ITEM_ACCESS_TOKENS = 4;
    public static final int HEADER_SERVERS = 5;
    public static final int ITEM_FAVOURITES = 6;
//    public static final int ITEM_LAN = 7;
    public static final int ITEM_PUBLIC = 8;
    public static final int HEADER_GENERAL = 9;
    public static final int ITEM_SETTINGS = 10;
    public static final int ITEM_VIDEO = 11;


    private static final int HEADER_TYPE = 0;
    private static final int ITEM_TYPE = 1;

    // TODO clean this up.

    public static class DrawerRow {
        int id;
        String title;

        private DrawerRow(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    public static class DrawerHeader extends DrawerRow {

        public DrawerHeader(int id, String title) {
            super(id, title);
        }
    }

    public static class DrawerItem extends DrawerRow {
        int icon;

        public DrawerItem(int id, String title, int icon) {
            super(id, title);
            this.icon = icon;
        }
    }






    private DrawerDataProvider mProvider;

    public DrawerAdapter(Context context, DrawerDataProvider provider) {
        super(context, 0);
        mProvider = provider;
        add(new DrawerAdapter.DrawerHeader(HEADER_CONNECTED_SERVER, context.getString(R.string.drawer_not_connected)));
        add(new DrawerAdapter.DrawerHeader(HEADER_GENERAL, context.getString(R.string.general)));
        add(new DrawerAdapter.DrawerItem(ITEM_SETTINGS, context.getString(R.string.action_settings), R.drawable.ic_action_settings));
        add(new DrawerAdapter.DrawerItem(ITEM_VIDEO, context.getString(R.string.action_settings), R.drawable.ic_action_settings));
    }



    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        View v = convertView;

        int viewType = getItemViewType(position);


        if(v == null) {
            if(viewType == HEADER_TYPE) {
                v = LayoutInflater.from(getContext()).inflate(R.layout.list_drawer_header, parent, false);
            }else if(viewType == ITEM_TYPE) {
                v = LayoutInflater.from(getContext()).inflate(R.layout.list_drawer_item, parent, false);
            }
        }


        DrawerHeader header = (DrawerHeader) getItem(position);
        TextView title = (TextView) v.findViewById(R.id.drawer_header_title);
        title.setText("PIANO1");


        if(viewType == HEADER_TYPE) {

            //DrawerHeader header = (DrawerHeader) getItem(position);
            //TextView title = (TextView) v.findViewById(R.id.drawer_header_title);
            //title.setText("PIANO1");

        } else if(viewType == ITEM_TYPE) {

            /*
            DrawerItem item = (DrawerItem) getItem(position);
            TextView title = (TextView) v.findViewById(R.id.drawer_item_title);
            ImageView icon = (ImageView) v.findViewById(R.id.drawer_item_icon);

            title.setText(item.title);

            icon.setImageResource(item.icon);

            boolean enabled = isEnabled(position);

            // Set text and icon color+alpha based on enabled/disabled state
            int textColor = title.getCurrentTextColor();
            textColor &= 0x00FFFFFF; // Clear alpha bits
            textColor |= enabled ? 0xFF000000 : 0x55000000; // Set alpha bits depending on whether the state is enabled or disabled
            title.setTextColor(textColor);
            icon.setColorFilter(textColor, PorterDuff.Mode.MULTIPLY);
            */
        }

        return v;
    }


    @Override
    public long getItemId(int position) {
        return getItem(position).id;
    }

    public DrawerRow getItemWithId(int id) {
        for(int x=0;x<getCount();x++) {
            DrawerRow row = getItem(x);
            if(row.id == id) return row;
        }
        return null;
    }


    @Override
    public boolean isEnabled(int position) {
        int viewType = getItemViewType(position);
        if(viewType == ITEM_TYPE) {
            switch ((int) getItemId(position)) {
                case ITEM_SERVER:
                case ITEM_INFO:
                case ITEM_ACCESS_TOKENS:
                case ITEM_PINNED_CHANNELS:
                    return mProvider.isConnected();
//                case ITEM_LAN:
//                    return false;
                default:
                    return true;
            }
        }
        return false; // Default false for headers
    }


    @Override
    public int getItemViewType(int position) {
        Object item = getItem(position);
        if(item instanceof DrawerHeader)
            return HEADER_TYPE;
        else if(item instanceof DrawerItem)
            return ITEM_TYPE;
        return ITEM_TYPE;
    }


    @Override
    public int getViewTypeCount() {
        return 2;
    }


}
